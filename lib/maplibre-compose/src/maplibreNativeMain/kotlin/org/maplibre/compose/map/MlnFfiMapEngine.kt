package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.createSnapshotTarget
import org.maplibre.compose.mlnffi.ensureMlnFfiConfigured
import org.maplibre.compose.mlnffi.withLock

/** How long [MapEngine.snapshot] parks between style-loaded polls. */
private const val STYLE_POLL_MILLIS = 8L

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

  internal var core: MlnFfiMapCore? = null
    private set

  private var coreScaleFactor = 0.0
  private var coreBackend: MapRenderBackend? = null
  private var closed = false

  /** Guards [activeSession] and [snapshotReserved], because a snapshot runs off the UI thread. */
  private val sessionLock = MlnFfiLock()

  /** The live render session; the shared core makes the adapter-level attach guard blind here. */
  private var activeSession: MlnFfiMapSession? = null

  /** True while [snapshot] holds the render slot, so a session cannot attach under it. */
  private var snapshotReserved = false

  /**
   * Creates the render session over [core], refusing a second session on the same live core. The
   * refusal happens at composition, before [MapState.attachSession] can state the same rule.
   */
  internal fun createSession(core: MlnFfiMapCore, backend: MapRenderBackend): MlnFfiMapSession =
    sessionLock.withLock {
      check(!snapshotReserved) { SNAPSHOT_SESSION_ERROR }
      val current = activeSession
      check(current == null || current.core !== core) { SINGLE_SESSION_ERROR }
      MlnFfiMapSession(core, backend).also { activeSession = it }
    }

  /** Forgets [session] once its composable leaves, so the next composable may create one. */
  internal fun releaseSession(session: MlnFfiMapSession) {
    sessionLock.withLock { if (activeSession === session) activeSession = null }
  }

  /** Returns the live core when [scaleFactor] and [backend] still match, or replaces it. */
  internal fun acquireCore(
    scaleFactor: Double,
    layoutDirection: LayoutDirection,
    backend: MapRenderBackend,
  ): MlnFfiMapCore {
    check(!closed) { "Cannot attach a render session to a closed map state" }
    core?.let { live ->
      if (coreScaleFactor == scaleFactor && coreBackend == backend) return live
      sessionLock.withLock {
        // An eviction under a live snapshot would destroy the core the snapshot is rendering.
        check(!snapshotReserved) { SNAPSHOT_SESSION_ERROR }
        // A live session must be evicted before its core closes, or it keeps rendering a destroyed
        // map; the close is idempotent with the session composable's own later dispose.
        activeSession?.let { session ->
          session.close()
          activeSession = null
        }
        // The loop's scale factor is fixed per map and a renderer is built for one backend.
        live.close()
      }
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

  /** Serializes snapshots: the map has one live render session, so two cannot pump at once. */
  private val snapshotMutex = Mutex()

  actual suspend fun snapshot(width: Dp, height: Dp, timeout: Duration): ImageBitmap {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    snapshotMutex.withLock {
      sessionLock.withLock {
        check(activeSession == null && state.attachedAdapter == null) {
          "MapState has an attached MaplibreMap; detach it before rendering a snapshot"
        }
        // The reservation holds the render slot until the finally below releases it, so a session
        // cannot attach or evict the core while the snapshot renders.
        snapshotReserved = true
      }
      try {
        return renderReservedSnapshot(width, height, deadline, timeout)
      } finally {
        sessionLock.withLock { snapshotReserved = false }
      }
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
      // A live core keeps its loaded style and scale; a detached bare state gets a fresh one.
      val core =
        core
          ?: acquireCore(
            scaleFactor = state.density.density.toDouble(),
            layoutDirection = state.layoutDirection,
            backend = target.backend,
          )
      // A session attach pushes the selected style; a snapshot has no session, so it pushes it
      // here. The core drops a style it already has.
      core.setBaseStyle(state.baseStyle)
      core.start()
      core.setCameraPosition(state.camera)
      awaitStyleLoaded(core, deadline, timeout)
      // One sync of the desired style content against the loaded style before rendering, so
      // content set on a detached state reaches the image.
      state.host.requestApplyChanges()
      state.host.awaitPendingWork()
      return renderStillImage(
        core = core,
        target = target,
        width = width.value.roundToInt(),
        height = height.value.roundToInt(),
        deadline = deadline,
        loadFailure = { state.lastLoadFailure.value },
      )
    } finally {
      target.close()
    }
  }

  private suspend fun awaitStyleLoaded(
    core: MlnFfiMapCore,
    deadline: TimeSource.Monotonic.ValueTimeMark,
    timeout: Duration,
  ) {
    while (!core.hasLoadedFirstStyle) {
      // The render loop fails a closed core the same way, so a close never waits out the timeout.
      check(!core.isClosed) { "MapState was closed while a snapshot was rendering" }
      state.lastLoadFailure.value?.let { reason ->
        throw IllegalStateException("The map failed to load: $reason")
      }
      check(deadline.hasNotPassedNow()) { "The style did not load within $timeout" }
      delay(STYLE_POLL_MILLIS)
    }
  }

  actual override fun close() {
    if (closed) return
    closed = true
    // The session closes before the core for the same reason acquireCore evicts before recreating.
    activeSession?.close()
    activeSession = null
    core?.close()
    core = null
  }
}

/** The snapshot flavor of the single-session rule, naming the conflict the caller can end. */
internal const val SNAPSHOT_SESSION_ERROR: String =
  "MapState is rendering a snapshot; one MapState renders one session at a time"
