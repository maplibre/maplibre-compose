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
import org.maplibre.compose.mlnffi.createSnapshotTarget
import org.maplibre.compose.mlnffi.ensureMlnFfiConfigured

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

  /** The live render session; the shared core makes the adapter-level attach guard blind here. */
  private var activeSession: MlnFfiMapSession? = null

  /**
   * Creates the render session over [core], refusing a second session on the same live core. The
   * refusal happens at composition, before [MapState.attachSession] can state the same rule.
   */
  internal fun createSession(core: MlnFfiMapCore, backend: MapRenderBackend): MlnFfiMapSession {
    val current = activeSession
    check(current == null || current.core !== core) { SINGLE_SESSION_ERROR }
    return MlnFfiMapSession(core, backend).also { activeSession = it }
  }

  /** Forgets [session] once its composable leaves, so the next composable may create one. */
  internal fun releaseSession(session: MlnFfiMapSession) {
    if (activeSession === session) activeSession = null
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
      // A live session must be evicted before its core closes, or it keeps rendering a destroyed
      // map; the close is idempotent with the session composable's own later dispose.
      activeSession?.let { session ->
        session.close()
        activeSession = null
      }
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

  /** Serializes snapshots: the map has one live render session, so two cannot pump at once. */
  private val snapshotMutex = Mutex()

  actual suspend fun snapshot(width: Dp, height: Dp, timeout: Duration): ImageBitmap {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    snapshotMutex.withLock {
      check(activeSession == null && state.attachedAdapter == null) {
        "MapState has an attached MaplibreMap; detach it before rendering a snapshot"
      }
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
  }

  private suspend fun awaitStyleLoaded(
    core: MlnFfiMapCore,
    deadline: TimeSource.Monotonic.ValueTimeMark,
    timeout: Duration,
  ) {
    while (!core.hasLoadedFirstStyle) {
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
