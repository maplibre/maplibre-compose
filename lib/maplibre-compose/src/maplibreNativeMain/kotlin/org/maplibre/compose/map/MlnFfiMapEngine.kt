package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.createSnapshotTarget
import org.maplibre.compose.mlnffi.ensureMlnFfiConfigured
import org.maplibre.compose.mlnffi.withLock

/** How long [MapEngine.captureStillImage] parks between style-loaded polls. */
private const val STYLE_POLL_MILLIS = 8L

/** mbgl's own zoom ceiling, so a snapshot renders as if no session had constrained the camera. */
private const val UNCONSTRAINED_MAX_ZOOM = 25.5

/** mbgl's own pitch ceiling. */
private const val UNCONSTRAINED_MAX_PITCH = 60.0

/**
 * Owns the [MlnFfiMapCore] behind a [MapState]. The core is created at the first session attach,
 * because that is the first moment the density and the render backend are known, and it survives
 * detach so a re-entering composition re-attaches to the live map instead of recreating it.
 */
internal actual class MapEngine actual constructor(private val state: MapState) : AutoCloseable {

  /**
   * The holder of the engine's one render slot. Every transition happens under [sessionLock], and
   * each illegal interleaving is rejected by the transition that would enter it.
   */
  private sealed interface Lifecycle {
    /** No session and no reservation; a retained core may still be live. */
    data object Detached : Lifecycle

    /** A composed session renders the core; the shared core makes the adapter guard blind here. */
    data class SessionAttached(val session: MlnFfiMapSession) : Lifecycle

    /** [captureStillImage] holds the render slot. */
    data object SnapshotReserved : Lifecycle

    data object Closed : Lifecycle
  }

  private var lifecycle: Lifecycle = Lifecycle.Detached

  /**
   * The core keeps its loaded style across detach, so the state keeps its binding while it lives.
   */
  actual val detachedAdapter: MapAdapter?
    get() = core

  // Volatile: replaced under sessionLock, read by unlocked detached writes and withPlatformMap.
  @Volatile
  internal var core: MlnFfiMapCore? = null
    private set

  private var coreScaleFactor = 0.0
  private var coreBackend: MapRenderBackend? = null

  /**
   * Guards [lifecycle] and the core's allocation and publication, because a snapshot runs off the
   * UI thread and a close may come from any.
   */
  private val sessionLock = MlnFfiLock()

  /** Creates the render session over [core]; the transition refuses every other slot holder. */
  internal fun createSession(core: MlnFfiMapCore, backend: MapRenderBackend): MlnFfiMapSession =
    sessionLock.withLock {
      when (val current = lifecycle) {
        Lifecycle.SnapshotReserved -> throw IllegalStateException(SNAPSHOT_SESSION_ERROR)
        Lifecycle.Closed ->
          throw IllegalStateException("Cannot attach a render session to a closed map state")
        is Lifecycle.SessionAttached ->
          check(current.session.core !== core) { SINGLE_SESSION_ERROR }
        Lifecycle.Detached -> {}
      }
      MlnFfiMapSession(core, backend).also { registerSessionLocked(it) }
    }

  /** Takes the render slot for [session]; the transition refuses every other slot holder. */
  internal fun registerSession(session: MlnFfiMapSession) {
    sessionLock.withLock {
      when (lifecycle) {
        Lifecycle.SnapshotReserved -> throw IllegalStateException(SNAPSHOT_SESSION_ERROR)
        Lifecycle.Closed ->
          throw IllegalStateException("Cannot attach a render session to a closed map state")
        is Lifecycle.SessionAttached -> throw IllegalStateException(SINGLE_SESSION_ERROR)
        Lifecycle.Detached -> {}
      }
      registerSessionLocked(session)
    }
  }

  private fun registerSessionLocked(session: MlnFfiMapSession) {
    session.core.attachRenderSession(session)
    lifecycle = Lifecycle.SessionAttached(session)
  }

  /** Forgets [session] once its composable leaves, so the next composable may create one. */
  internal fun releaseSession(session: MlnFfiMapSession) {
    sessionLock.withLock {
      val current = lifecycle
      if (current is Lifecycle.SessionAttached && current.session === session) {
        lifecycle = Lifecycle.Detached
        // The departed target's dimensions are stale, so the next bounds fit waits for a real one.
        core?.resetAttachedViewport()
        core?.endCameraTransitionsForDetach()
      }
    }
  }

  /** Returns the live core when [scaleFactor] and [backend] still match, or replaces it. */
  internal fun acquireCore(
    scaleFactor: Double,
    layoutDirection: LayoutDirection,
    backend: MapRenderBackend,
  ): MlnFfiMapCore = sessionLock.withLock {
    // An eviction under a live snapshot would destroy the core the snapshot is rendering; the
    // snapshot path replaces its own core through acquireCoreLocked as the reservation holder.
    check(lifecycle != Lifecycle.SnapshotReserved) { SNAPSHOT_SESSION_ERROR }
    acquireCoreLocked(scaleFactor, layoutDirection, backend)
  }

  /**
   * Returns the live core when [scaleFactor] and [backend] still match, or builds an unpublished
   * replacement. A composition may be abandoned, so nothing here evicts: [publishCore] installs the
   * replacement and [discardCore] closes an abandoned one.
   */
  internal fun obtainCore(
    scaleFactor: Double,
    layoutDirection: LayoutDirection,
    backend: MapRenderBackend,
  ): MlnFfiMapCore = sessionLock.withLock {
    check(lifecycle != Lifecycle.Closed) { "Cannot attach a render session to a closed map state" }
    core?.let { live ->
      // A core whose runtime loop died cannot recover; its replacement publishes over it.
      if (
        coreScaleFactor == scaleFactor &&
          coreBackend == backend &&
          live.runtimeLoop?.failure == null
      )
        return@withLock live
    }
    MlnFfiMapCore(
      callbacks = state.callbacks,
      logger = state.logger,
      scaleFactor = scaleFactor,
      layoutDirection = layoutDirection,
    )
  }

  /** Installs an [obtainCore] replacement, evicting the previous session and core. */
  internal fun publishCore(pending: MlnFfiMapCore, scaleFactor: Double, backend: MapRenderBackend) {
    sessionLock.withLock {
      if (core === pending) return
      check(lifecycle != Lifecycle.SnapshotReserved) { SNAPSHOT_SESSION_ERROR }
      check(lifecycle != Lifecycle.Closed) {
        "Cannot attach a render session to a closed map state"
      }
      // A rival composable must not evict the session that owns the slot; a legitimate density or
      // backend change disposes the old resource before its replacement publishes.
      check(lifecycle !is Lifecycle.SessionAttached) { SINGLE_SESSION_ERROR }
      // The dying core produced any pending detached-load completion and load failure.
      state.clearDetachedLoadReplay()
      state.lastLoadFailure.value = null
      core?.close()
      core = pending
      coreScaleFactor = scaleFactor
      coreBackend = backend
    }
  }

  /** Closes an [obtainCore] replacement that an abandoned composition never published. */
  internal fun discardCore(pending: MlnFfiMapCore) {
    sessionLock.withLock { if (core !== pending) pending.close() }
  }

  /**
   * The acquire body, under [sessionLock] so allocation and publication cannot race [close] or a
   * snapshot's reservation.
   */
  private fun acquireCoreLocked(
    scaleFactor: Double,
    layoutDirection: LayoutDirection,
    backend: MapRenderBackend,
  ): MlnFfiMapCore {
    check(lifecycle != Lifecycle.Closed) { "Cannot attach a render session to a closed map state" }
    core?.let { live ->
      // A core whose runtime loop died cannot recover, so a match on it still replaces it.
      if (
        coreScaleFactor == scaleFactor &&
          coreBackend == backend &&
          live.runtimeLoop?.failure == null
      )
        return live
      // A live session must be evicted before its core closes, or it keeps rendering a destroyed
      // map; the close is idempotent with the session composable's own later dispose.
      (lifecycle as? Lifecycle.SessionAttached)?.let { attached ->
        attached.session.close()
        lifecycle = Lifecycle.Detached
      }
      // The dying core produced any pending detached-load completion and load failure.
      state.clearDetachedLoadReplay()
      state.lastLoadFailure.value = null
      // The loop's scale factor is fixed per map and a renderer is built for one backend.
      live.close()
    }
    val created =
      MlnFfiMapCore(
        callbacks = state.callbacks,
        logger = state.logger,
        scaleFactor = scaleFactor,
        layoutDirection = layoutDirection,
      )
    core = created
    coreScaleFactor = scaleFactor
    coreBackend = backend
    return created
  }

  /** Takes the render slot for a snapshot; the transition refuses every other slot holder. */
  private fun reserveSnapshot() {
    sessionLock.withLock {
      check(lifecycle != Lifecycle.Closed) {
        "MapState is closed; a closed state cannot render a still image"
      }
      check(lifecycle == Lifecycle.Detached && state.attachedAdapter == null) {
        "MapState has an attached MaplibreMap; detach it before rendering a still image"
      }
      lifecycle = Lifecycle.SnapshotReserved
    }
  }

  /** Returns the render slot after a snapshot; a close during the snapshot stays closed. */
  private fun releaseSnapshot() {
    sessionLock.withLock {
      if (lifecycle == Lifecycle.SnapshotReserved) lifecycle = Lifecycle.Detached
    }
  }

  /**
   * A composition that never quiesces, such as one animating on the frame clock, fails the capture
   * at [timeout] instead of holding the reservation forever.
   */
  private suspend fun awaitQuiescentOrFail(
    deadline: TimeSource.Monotonic.ValueTimeMark,
    timeout: Duration,
  ) {
    val remaining = -deadline.elapsedNow()
    check(remaining.isPositive()) {
      "The style composition did not settle within $timeout; is the content animating?"
    }
    withTimeoutOrNull(remaining) { state.host.awaitPendingWork() }
      ?: throw IllegalStateException(
        "The style composition did not settle within $timeout; is the content animating?"
      )
  }

  /** Serializes snapshots: the map has one live render session, so two cannot pump at once. */
  private val snapshotMutex = Mutex()

  actual suspend fun captureStillImage(width: Dp, height: Dp, timeout: Duration): ImageBitmap {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    // The wait for another capture is bounded by this call's own deadline.
    withTimeoutOrNull(timeout) { snapshotMutex.lock() }
      ?: throw IllegalStateException("Another still image held the renderer past $timeout")
    return try {
      snapshotLocked(width, height, timeout, deadline)
    } finally {
      snapshotMutex.unlock()
    }
  }

  private suspend fun snapshotLocked(
    width: Dp,
    height: Dp,
    timeout: Duration,
    deadline: TimeSource.Monotonic.ValueTimeMark,
  ): ImageBitmap {
    // The reservation holds the render slot until the finally below releases it, so a session
    // cannot attach or evict the core while the snapshot renders.
    reserveSnapshot()
    try {
      return renderReservedSnapshot(width, height, deadline, timeout)
    } finally {
      releaseSnapshot()
    }
  }

  private suspend fun renderReservedSnapshot(
    width: Dp,
    height: Dp,
    deadline: TimeSource.Monotonic.ValueTimeMark,
    timeout: Duration,
  ): ImageBitmap {
    ensureMlnFfiConfigured()
    state.ensureBaseStyleSelected()
    val target = createSnapshotTarget()
    try {
      // A live core keeps its loaded style and scale only while its backend matches the snapshot
      // target's; a mismatch or a detached bare state gets a fresh one, allocated under the lock
      // as the reservation holder so a close cannot orphan it.
      val core = sessionLock.withLock {
        val retainedMatches = core != null && coreBackend == target.backend
        acquireCoreLocked(
          scaleFactor = if (retainedMatches) coreScaleFactor else state.density.density.toDouble(),
          layoutDirection = state.layoutDirection,
          backend = target.backend,
        )
      }
      // A session attach pushes the selected style; a snapshot has no session, so it pushes it
      // here, serialized with the public setter. The core drops a style it already has.
      state.replaySelectedStyle(core)
      core.start()
      // A retained session's camera constraints must not clamp the snapshot; the next session
      // attach restores them through its SessionOptions.
      core.setCameraConstraints(
        CameraConstraints(
          minZoom = 0.0,
          maxZoom = UNCONSTRAINED_MAX_ZOOM,
          minPitch = 0.0,
          maxPitch = UNCONSTRAINED_MAX_PITCH,
          boundingBox = null,
        )
      )
      core.setCameraPadding(PaddingValues(0.dp))
      core.replayCameraRecord { state.camera }
      awaitStyleLoaded(core, deadline, timeout)
      // One sync of the desired style composition against the loaded style before rendering.
      state.host.requestApplyChanges()
      awaitQuiescentOrFail(deadline, timeout)
      return renderStillImage(
        core = core,
        target = target,
        width = width.value.roundToInt(),
        height = height.value.roundToInt(),
        deadline = deadline,
        loadFailure = { state.lastLoadFailure.value },
        onViewportReady = {
          // The target's dimensions are published, so viewport-conditioned content can compose.
          state.viewportState.value = core.getViewport()
          // One more sync so that content reaches the loaded style before the final frame.
          state.host.requestApplyChanges()
          awaitQuiescentOrFail(deadline, timeout)
        },
      )
    } finally {
      target.close()
      // The snapshot's target stamped its own dimensions on the retained map.
      sessionLock.withLock { core?.resetAttachedViewport() }
      // The published viewport died with the target; the next attach publishes its own.
      state.viewportState.value = null
    }
  }

  private suspend fun awaitStyleLoaded(
    core: MlnFfiMapCore,
    deadline: TimeSource.Monotonic.ValueTimeMark,
    timeout: Duration,
  ) {
    // hasLoadedFirstStyle is sticky across style changes, so the wait compares generations, and
    // re-reads the requested one so a selection made during the wait is also waited for.
    while (core.loadedStyleGeneration < core.requestedStyleGeneration) {
      // The render loop fails a closed core the same way, so a close never waits out the timeout.
      check(!core.isClosed) { "MapState was closed while a still image was rendering" }
      state.lastLoadFailure.value?.let { reason ->
        throw IllegalStateException("The map failed to load: $reason")
      }
      check(deadline.hasNotPassedNow()) { "The style did not load within $timeout" }
      delay(STYLE_POLL_MILLIS)
    }
  }

  actual override fun close() {
    // Under the lock so a concurrent acquire either fails the closed check or publishes a core
    // this close then sees; the lock's critical sections are all brief, so close stays prompt and
    // a snapshot mid-render fails through the core's own closed state.
    sessionLock.withLock {
      if (lifecycle == Lifecycle.Closed) return@withLock
      // The session closes before the core for the same reason acquireCoreLocked evicts before
      // recreating.
      (lifecycle as? Lifecycle.SessionAttached)?.session?.close()
      lifecycle = Lifecycle.Closed
      core?.close()
      core = null
    }
  }
}

/** The snapshot flavor of the single-session rule, naming the conflict the caller can end. */
internal const val SNAPSHOT_SESSION_ERROR: String =
  "MapState is rendering a still image; one MapState renders one session at a time"
