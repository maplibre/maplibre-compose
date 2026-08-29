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
import org.maplibre.compose.mlnffi.requireSnapshotSupported
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

  /** The composed render session wired to [core], or null. */
  private var attachedSession: MlnFfiMapSession? = null

  private var closed = false

  /**
   * Guards core allocation and the attached session pointer, because a snapshot runs off the UI
   * thread and a close may come from any. The render slot itself is [MapRecord.renderer].
   */
  private val sessionLock = MlnFfiLock()

  /**
   * Mutates the core under [sessionLock], then publishes a replacement after the lock is released.
   * [MapState.replaceCore] hops to the host, and [MapState.close] takes this lock after that hop,
   * so a publish inside the lock deadlocks a concurrent close.
   */
  private inline fun <T> mutateCore(block: () -> T): T {
    var published: MlnFfiMapCore? = null
    val result = sessionLock.withLock {
      val previous = core
      val result = block()
      if (core !== previous) published = core
      result
    }
    published?.let(state::replaceCore)
    return result
  }

  private fun refuseUnlessOpen() {
    check(!closed) { "Cannot attach a render session to a closed map state" }
    check(!state.isCapturing) { SNAPSHOT_SESSION_ERROR }
  }

  /** Creates the render session over [core]; the record lease refuses every other slot holder. */
  internal fun createSession(core: MlnFfiMapCore, backend: MapRenderBackend): MlnFfiMapSession =
    sessionLock.withLock {
      refuseUnlessOpen()
      attachedSession?.let { check(it.core !== core) { SINGLE_SESSION_ERROR } }
      MlnFfiMapSession(core, backend).also { registerSessionLocked(it) }
    }

  /** Takes the render slot for [session]; the record lease refuses every other slot holder. */
  internal fun registerSession(session: MlnFfiMapSession) {
    sessionLock.withLock {
      refuseUnlessOpen()
      check(attachedSession == null) { SINGLE_SESSION_ERROR }
      registerSessionLocked(session)
    }
  }

  private fun registerSessionLocked(session: MlnFfiMapSession) {
    session.core.attachRenderSession(session)
    attachedSession = session
  }

  /** Forgets [session] once its composable leaves, so the next composable may create one. */
  internal fun releaseSession(session: MlnFfiMapSession) {
    sessionLock.withLock {
      if (attachedSession === session) {
        attachedSession = null
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
  ): MlnFfiMapCore = mutateCore {
    // An eviction under a live snapshot would destroy the core the snapshot is rendering; the
    // snapshot path replaces its own core through acquireCoreLocked as the lease holder.
    check(!state.isCapturing) { SNAPSHOT_SESSION_ERROR }
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
    check(!closed) { "Cannot attach a render session to a closed map state" }
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
    mutateCore {
      if (core === pending) return@mutateCore
      check(!state.isCapturing) { SNAPSHOT_SESSION_ERROR }
      check(!closed) { "Cannot attach a render session to a closed map state" }
      // A rival composable must not evict the session that owns the slot; a legitimate density or
      // backend change disposes the old resource before its replacement publishes.
      check(attachedSession == null) { SINGLE_SESSION_ERROR }
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
   * The acquire body, under [sessionLock] so allocation cannot race [close] or a snapshot's
   * reservation. The caller publishes a replacement through [mutateCore] after the lock is
   * released.
   */
  private fun acquireCoreLocked(
    scaleFactor: Double,
    layoutDirection: LayoutDirection,
    backend: MapRenderBackend,
  ): MlnFfiMapCore {
    check(!closed) { "Cannot attach a render session to a closed map state" }
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
      attachedSession?.let { attached ->
        attached.close()
        attachedSession = null
      }
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

  actual fun requireStillImageSupported() {
    requireSnapshotSupported()
  }

  actual suspend fun captureStillImage(
    width: Dp,
    height: Dp,
    timeout: Duration,
    capture: RendererState.Capture,
  ): ImageBitmap {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    // The wait for another capture is bounded by this call's own deadline.
    withTimeoutOrNull(timeout) { snapshotMutex.lock() }
      ?: throw IllegalStateException("Another still image held the renderer past $timeout")
    return try {
      renderReservedSnapshot(width, height, deadline, timeout, capture)
    } finally {
      snapshotMutex.unlock()
    }
  }

  private suspend fun renderReservedSnapshot(
    width: Dp,
    height: Dp,
    deadline: TimeSource.Monotonic.ValueTimeMark,
    timeout: Duration,
    capture: RendererState.Capture,
  ): ImageBitmap {
    ensureMlnFfiConfigured()
    val target = createSnapshotTarget()
    try {
      // A live core keeps its loaded style and scale only while its backend matches the snapshot
      // target's; a mismatch or a detached bare state gets a fresh one, allocated under the lock
      // as the reservation holder so a close cannot orphan it.
      val core = mutateCore {
        // A departed session can leave a core at a different scale than the restored density.
        acquireCoreLocked(
          scaleFactor = state.density.density.toDouble(),
          layoutDirection = state.layoutDirection,
          backend = target.backend,
        )
      }
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
      core.replayCameraRecord { capture.camera }
      awaitStyleLoaded(core, capture.styleGeneration, deadline, timeout)
      state.host.requestApplyChanges()
      awaitQuiescentOrFail(deadline, timeout)
      return renderStillImage(
        core = core,
        target = target,
        width = width.value.roundToInt(),
        height = height.value.roundToInt(),
        deadline = deadline,
        loadFailure = { state.captureRenderFailure(capture.styleGeneration) },
        onViewportReady = {
          state.onCaptureViewport(core.getViewport())
          state.host.requestApplyChanges()
          awaitQuiescentOrFail(deadline, timeout)
        },
      )
    } finally {
      target.close()
      // The snapshot's target stamped its own dimensions on the retained map.
      sessionLock.withLock { core?.resetAttachedViewport() }
    }
  }

  private suspend fun awaitStyleLoaded(
    core: MlnFfiMapCore,
    styleGeneration: Long,
    deadline: TimeSource.Monotonic.ValueTimeMark,
    timeout: Duration,
  ) {
    // Wait only for the generation frozen at capture. A later baseStyle write is not this image.
    while (core.loadedStyleGeneration < styleGeneration) {
      // The render loop fails a closed core the same way, so a close never waits out the timeout.
      check(!core.isClosed) { "MapState was closed while a still image was rendering" }
      state.captureRenderFailure(styleGeneration)?.let { reason ->
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
      if (closed) return@withLock
      // The session closes before the core for the same reason acquireCoreLocked evicts before
      // recreating.
      attachedSession?.close()
      attachedSession = null
      closed = true
      core?.close()
      core = null
    }
  }
}
