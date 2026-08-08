package org.maplibre.compose.mlnffi

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import co.touchlab.kermit.Logger

/** Where a map surface is in its lifecycle. */
internal sealed interface MlnFfiMapSurfaceState {
  /** No host has been created yet. */
  data object Initializing : MlnFfiMapSurfaceState

  /** A host is live and frames can be produced. */
  data class Ready(val backends: RenderBackendPair) : MlnFfiMapSurfaceState

  /** No backend the host and the runtime both support. Not an error the application can retry. */
  data class Unavailable(val diagnostic: String) : MlnFfiMapSurfaceState

  /** A host existed but something went wrong. */
  data class Failed(val diagnostic: String, val cause: Throwable? = null) : MlnFfiMapSurfaceState
}

/** Hosts [renderer] on a Compose drawing surface, driving the frame loop. */
@Composable
internal fun MlnFfiMapSurface(
  renderer: MlnFfiMapRenderer,
  runtimeBackends: Set<MapRenderBackend>,
  factory: MlnFfiMapHostFactory,
  modifier: Modifier = Modifier,
  logger: Logger? = null,
  onStateChanged: (MlnFfiMapSurfaceState) -> Unit = {},
) {
  val density = LocalDensity.current
  val stateObserver = LocalMlnFfiMapSurfaceStateObserver.current
  var extent by remember { mutableStateOf(MlnFfiMapExtent.Empty) }
  var frameRequest by remember { mutableLongStateOf(0L) }
  var state by remember {
    mutableStateOf<MlnFfiMapSurfaceState>(MlnFfiMapSurfaceState.Initializing)
  }
  val drawState = remember { MlnFfiMapDrawState() }

  // The host owns the GPU objects on both sides of the bridge, so they cannot outlive it: a factory
  // or backend change must recreate it.
  val hostResult =
    remember(factory, runtimeBackends, renderer) {
      createHost(runtimeBackends, factory, renderer.backend)
    }
  val host = (hostResult as? HostCreation.Created)?.host
  val session = remember(host) { host?.let { MlnFfiMapHostSessionImpl(it) { frameRequest += 1 } } }

  LaunchedEffect(state) {
    when (val current = state) {
      is MlnFfiMapSurfaceState.Failed -> logger?.e(current.cause) { current.diagnostic }
      is MlnFfiMapSurfaceState.Unavailable -> logger?.w { current.diagnostic }
      else -> Unit
    }
    onStateChanged(state)
    stateObserver?.invoke(state)
  }

  val creation = hostResult
  DisposableEffect(host, creation, session, renderer) {
    state =
      when (creation) {
        is HostCreation.Created -> {
          checkNotNull(session)
          renderer.onSurfaceAvailable(session)
          session.requestFrame()
          MlnFfiMapSurfaceState.Ready(creation.host.backends)
        }
        is HostCreation.Unavailable -> {
          logger?.w { creation.diagnostic }
          drawState.closeRenderer(renderer, logger)
          MlnFfiMapSurfaceState.Unavailable(creation.diagnostic)
        }
        is HostCreation.Failed -> {
          logger?.e(creation.cause) { creation.diagnostic }
          drawState.closeRenderer(renderer, logger)
          MlnFfiMapSurfaceState.Failed(creation.diagnostic, creation.cause)
        }
      }

    onDispose {
      // Idempotent when a terminal creation state already closed it above.
      drawState.closeRenderer(renderer, logger)
      if (host != null) {
        // The renderer must close before the host: it drops its references to host-owned targets,
        // and it reaches its owner thread through the still-live host session.
        runCatching { host.close() }.onFailure { logger?.e(it) { "Map host failed to close" } }
      }
      drawState.reset()
      state = MlnFfiMapSurfaceState.Initializing
    }
  }

  LaunchedEffect(extent, host, renderer, state is MlnFfiMapSurfaceState.Ready) {
    if (host == null || extent.isEmpty || state !is MlnFfiMapSurfaceState.Ready) {
      return@LaunchedEffect
    }
    try {
      host.resize(extent)
      renderer.onSurfaceChanged(extent)
      session?.requestFrame()
    } catch (error: Throwable) {
      state =
        MlnFfiMapSurfaceState.Failed(
          "Map host failed to resize to ${extent.width}x${extent.height}",
          error,
        )
      drawState.closeRenderer(renderer, logger)
    }
  }

  Canvas(
    modifier =
      modifier.onSizeChanged { size ->
        extent = MlnFfiMapExtent.fromPhysical(size.width, size.height, density.density.toDouble())
      }
  ) {
    // Load-bearing read: it is what makes a requestFrame() call reschedule this Canvas.
    frameRequest

    var drew = false
    if (host != null && session != null && !extent.isEmpty) {
      val currentState = state
      if (currentState is MlnFfiMapSurfaceState.Ready) {
        val frameId = drawState.nextFrameId()
        try {
          when (val acquisition = host.acquireFrame(frameId, extent, System.nanoTime())) {
            MlnFfiMapFrameAcquisition.NotReady -> {
              // A documented startup state, not a lost device. It neither spends nor resets the
              // recovery budget; ask until Compose creates its context.
              session.requestFrame()
            }
            is MlnFfiMapFrameAcquisition.Acquired -> {
              val frame = acquisition.frame
              try {
                when (host.withProducerAccess(frame) { renderer.render(frame) }) {
                  MlnFfiFrameResult.RENDERED -> {
                    host.completeProducerAccess(frame)
                    drawState.lastCompletedTarget = frame.target
                  }
                  MlnFfiFrameResult.SKIPPED -> Unit
                }
                drawState.lastCompletedTarget?.let { drew = host.draw(this, it) }
              } finally {
                runCatching { host.releaseFrame(frame) }
                  .onFailure { logger?.e(it) { "Map host failed to release frame $frameId" } }
              }
              // A skipped renderer frame still acquired and drew, so it clears the budget too.
              drawState.onFrameSucceeded()
            }
          }
        } catch (error: Throwable) {
          if (error is VirtualMachineError) throw error
          val recovered =
            recoverFromFrameFailure(
              ready = currentState,
              renderer = renderer,
              session = session,
              drawState = drawState,
              frameId = frameId,
              error = error,
              logger = logger,
            )
          state = recovered
          if (recovered is MlnFfiMapSurfaceState.Failed) {
            drawState.closeRenderer(renderer, logger)
          }
        }
      }
    }

    if (!drew) drawRect(Color.Transparent)
  }
}

/**
 * How many consecutive frames may fail and be retried before the surface gives up.
 *
 * A device lost across a sleep/wake cycle needs exactly one rebuild, so a second consecutive
 * failure already says the rebuild is not the answer; three leaves room for a driver that reports
 * the loss again while it resets. The bound keeps a GPU that is genuinely gone from failing every
 * frame for as long as the window is open.
 */
private const val MAX_FRAME_RECOVERY_ATTEMPTS = 3

/** Rebuilds the render session after a frame failed, or latches the surface as failed. */
private fun recoverFromFrameFailure(
  ready: MlnFfiMapSurfaceState.Ready,
  renderer: MlnFfiMapRenderer,
  session: MlnFfiMapHostSession,
  drawState: MlnFfiMapDrawState,
  frameId: Long,
  error: Throwable,
  logger: Logger?,
): MlnFfiMapSurfaceState {
  if (error is MlnFfiFatalFrameException) {
    return MlnFfiMapSurfaceState.Failed("Map frame $frameId failed fatally", error)
  }

  val attempt = drawState.recordFrameFailure()
  if (attempt > MAX_FRAME_RECOVERY_ATTEMPTS) {
    return MlnFfiMapSurfaceState.Failed(
      "Map frame $frameId failed after $MAX_FRAME_RECOVERY_ATTEMPTS attempts to rebuild " +
        "the render session",
      error,
    )
  }

  logger?.w(error) {
    "Map frame $frameId failed; rebuilding the render session " +
      "(attempt $attempt of $MAX_FRAME_RECOVERY_ATTEMPTS)"
  }
  // Dropped before the renderer is told, so the host is not asked to draw a handle it is about to
  // reallocate.
  drawState.lastCompletedTarget = null
  runCatching { renderer.onSurfaceLost() }
    .onFailure { logger?.e(it) { "Map renderer failed to release the lost surface" } }

  return try {
    renderer.onSurfaceAvailable(session)
    // Nothing else will ask: an idle map publishes no update, so this frame is the retry.
    session.requestFrame()
    ready
  } catch (rearmError: Throwable) {
    if (rearmError is VirtualMachineError) throw rearmError
    MlnFfiMapSurfaceState.Failed(
      "Map renderer failed to take the surface back after frame $frameId",
      rearmError,
    )
  }
}

private sealed interface HostCreation {
  data class Created(val host: MlnFfiMapHost) : HostCreation

  data class Unavailable(val diagnostic: String) : HostCreation

  data class Failed(val diagnostic: String, val cause: Throwable?) : HostCreation
}

private fun createHost(
  runtimeBackends: Set<MapRenderBackend>,
  factory: MlnFfiMapHostFactory,
  rendererBackend: MapRenderBackend?,
): HostCreation {
  val selection =
    selectBackends(
      runtimeBackends = runtimeBackends,
      hostBackends = factory.supportedBackends,
      hostDescription = factory.description,
      operatingSystem = System.getProperty("os.name") ?: "unknown",
      architecture = System.getProperty("os.arch") ?: "unknown",
    )

  val backends =
    when (selection) {
      is BackendSelection.Selected -> selection.backends
      is BackendSelection.Unavailable -> return HostCreation.Unavailable(selection.diagnostic)
    }

  if (rendererBackend != null && rendererBackend != backends.producer) {
    return HostCreation.Unavailable(
      "The map renderer requires $rendererBackend but the selected backend is " +
        "${backends.producer}."
    )
  }

  return try {
    when (val result = factory.create(backends.producer)) {
      is MlnFfiMapHostResult.Created -> HostCreation.Created(result.host)
      is MlnFfiMapHostResult.Failed -> HostCreation.Failed(result.diagnostic, result.cause)
    }
  } catch (error: Throwable) {
    if (error is VirtualMachineError) throw error
    HostCreation.Failed("${factory.description} threw while creating a map host", error)
  }
}

private class MlnFfiMapDrawState {
  private var nextFrameId = 1L
  private var rendererClosed = false

  /**
   * The last target that was rendered into, redrawn when the renderer skips a frame.
   *
   * Deliberately survives a resize — one stretched frame beats blanking through a resize drag — at
   * the cost of a rule for hosts, recorded on [MlnFfiRenderTarget.generation]: a retired target has
   * to stay presentable until a newer one has been drawn.
   */
  var lastCompletedTarget: MlnFfiRenderTarget? = null

  /** Frames that failed since the last one that did not; consecutive rather than cumulative. */
  var frameFailures: Int = 0
    private set

  fun nextFrameId(): Long = nextFrameId++

  /** Counts a failed frame and reports which attempt at recovering it is. */
  fun recordFrameFailure(): Int = ++frameFailures

  fun onFrameSucceeded() {
    frameFailures = 0
  }

  /** Stops map work once a failed surface can no longer produce the frames it depends on. */
  fun closeRenderer(renderer: MlnFfiMapRenderer, logger: Logger?) {
    if (rendererClosed) return
    rendererClosed = true
    runCatching { renderer.close() }.onFailure { logger?.e(it) { "Map renderer failed to close" } }
  }

  fun reset() {
    lastCompletedTarget = null
    frameFailures = 0
    rendererClosed = false
  }
}

private class MlnFfiMapHostSessionImpl(
  private val host: MlnFfiMapHost,
  private val onRequestFrame: () -> Unit,
) : MlnFfiMapHostSession {
  override val backends: RenderBackendPair
    get() = host.backends

  override fun requestFrame() {
    onRequestFrame()
  }

  override fun <T> withRendererAccess(action: () -> T): T = host.withRendererAccess(action)
}
