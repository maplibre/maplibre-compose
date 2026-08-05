package org.maplibre.compose.desktop

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

/** Where a desktop map surface is in its lifecycle. */
internal sealed interface DesktopMapSurfaceState {
  /** No host has been created yet. */
  data object Initializing : DesktopMapSurfaceState

  /** A host is live and frames can be produced. */
  data class Ready(val backends: DesktopBackendPair) : DesktopMapSurfaceState

  /** No usable backend, or the host factory declined. Not an error the application can retry. */
  data class Unavailable(val diagnostic: String) : DesktopMapSurfaceState

  /** A host existed but something went wrong. */
  data class Failed(val diagnostic: String, val cause: Throwable? = null) : DesktopMapSurfaceState
}

/** Hosts [renderer] on a Compose drawing surface, driving the frame loop. */
@Composable
internal fun DesktopMapSurface(
  renderer: DesktopMapRenderer,
  runtimeBackends: Set<MapRenderBackend>,
  factory: DesktopMapHostFactory,
  modifier: Modifier = Modifier,
  logger: Logger? = null,
  onStateChanged: (DesktopMapSurfaceState) -> Unit = {},
) {
  val density = LocalDensity.current
  var extent by remember { mutableStateOf(DesktopMapExtent.Empty) }
  var frameRequest by remember { mutableLongStateOf(0L) }
  var state by remember {
    mutableStateOf<DesktopMapSurfaceState>(DesktopMapSurfaceState.Initializing)
  }
  val drawState = remember { DesktopMapDrawState() }

  // The host owns the GPU objects on both sides of the bridge, so they cannot outlive it: a factory
  // or backend change must recreate it.
  val hostResult =
    remember(factory, runtimeBackends, renderer) {
      createHost(runtimeBackends, factory, renderer.backend)
    }
  val host = (hostResult as? HostCreation.Created)?.host
  val session = remember(host) { host?.let { DesktopMapHostSessionImpl(it) { frameRequest += 1 } } }

  LaunchedEffect(state) {
    when (val current = state) {
      is DesktopMapSurfaceState.Failed -> logger?.e(current.cause) { current.diagnostic }
      is DesktopMapSurfaceState.Unavailable -> logger?.w { current.diagnostic }
      else -> Unit
    }
    onStateChanged(state)
  }

  DisposableEffect(host, hostResult, session, renderer) {
    state =
      when (hostResult) {
        is HostCreation.Created -> {
          checkNotNull(session)
          renderer.onSurfaceAvailable(session)
          session.requestFrame()
          DesktopMapSurfaceState.Ready(hostResult.host.backends)
        }
        is HostCreation.Unavailable -> {
          logger?.w { hostResult.diagnostic }
          DesktopMapSurfaceState.Unavailable(hostResult.diagnostic)
        }
        is HostCreation.Failed -> {
          logger?.e(hostResult.cause) { hostResult.diagnostic }
          DesktopMapSurfaceState.Failed(hostResult.diagnostic, hostResult.cause)
        }
      }

    onDispose {
      if (host != null) {
        // The renderer must close before the host: it drops its references to host-owned targets,
        // and it reaches its owner thread through the still-live host session.
        runCatching { renderer.close() }
          .onFailure { logger?.e(it) { "Desktop map renderer failed to close" } }
        runCatching { host.close() }
          .onFailure { logger?.e(it) { "Desktop map host failed to close" } }
      }
      drawState.reset()
      state = DesktopMapSurfaceState.Initializing
    }
  }

  LaunchedEffect(extent, host, renderer, state is DesktopMapSurfaceState.Ready) {
    if (host == null || extent.isEmpty || state !is DesktopMapSurfaceState.Ready) {
      return@LaunchedEffect
    }
    try {
      host.resize(extent)
      renderer.onSurfaceChanged(extent)
      session?.requestFrame()
    } catch (error: Throwable) {
      state =
        DesktopMapSurfaceState.Failed(
          "Desktop map host failed to resize to ${extent.width}x${extent.height}",
          error,
        )
    }
  }

  Canvas(
    modifier =
      modifier.onSizeChanged { size ->
        extent = DesktopMapExtent.fromPhysical(size.width, size.height, density.density.toDouble())
      }
  ) {
    // Load-bearing read: it is what makes a requestFrame() call reschedule this Canvas.
    frameRequest

    var drew = false
    if (host != null && session != null && !extent.isEmpty) {
      val currentState = state
      if (currentState is DesktopMapSurfaceState.Ready) {
        val frameId = drawState.nextFrameId()
        try {
          val frame = host.acquireFrame(frameId, extent, System.nanoTime())
          try {
            when (host.withProducerAccess(frame) { renderer.render(frame) }) {
              DesktopFrameResult.RENDERED -> {
                host.completeProducerAccess(frame)
                drawState.lastCompletedTarget = frame.target
              }
              DesktopFrameResult.SKIPPED -> Unit
            }
            drawState.lastCompletedTarget?.let { drew = host.draw(this, it) }
          } finally {
            runCatching { host.releaseFrame(frame) }
              .onFailure { logger?.e(it) { "Desktop map host failed to release frame $frameId" } }
          }
          // A skipped frame still acquired, rendered, and drew, so it clears the budget too.
          drawState.onFrameSucceeded()
        } catch (error: Throwable) {
          if (error is VirtualMachineError) throw error
          state =
            recoverFromFrameFailure(
              ready = currentState,
              renderer = renderer,
              session = session,
              drawState = drawState,
              frameId = frameId,
              error = error,
              logger = logger,
            )
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
  ready: DesktopMapSurfaceState.Ready,
  renderer: DesktopMapRenderer,
  session: DesktopMapHostSession,
  drawState: DesktopMapDrawState,
  frameId: Long,
  error: Throwable,
  logger: Logger?,
): DesktopMapSurfaceState {
  if (error is DesktopMapFatalFrameException) {
    return DesktopMapSurfaceState.Failed("Desktop map frame $frameId failed fatally", error)
  }

  val attempt = drawState.recordFrameFailure()
  if (attempt > MAX_FRAME_RECOVERY_ATTEMPTS) {
    return DesktopMapSurfaceState.Failed(
      "Desktop map frame $frameId failed after $MAX_FRAME_RECOVERY_ATTEMPTS attempts to rebuild " +
        "the render session",
      error,
    )
  }

  logger?.w(error) {
    "Desktop map frame $frameId failed; rebuilding the render session " +
      "(attempt $attempt of $MAX_FRAME_RECOVERY_ATTEMPTS)"
  }
  // Dropped before the renderer is told, so the host is not asked to draw a handle it is about to
  // reallocate.
  drawState.lastCompletedTarget = null
  runCatching { renderer.onSurfaceLost() }
    .onFailure { logger?.e(it) { "Desktop map renderer failed to release the lost surface" } }

  return try {
    renderer.onSurfaceAvailable(session)
    // Nothing else will ask: an idle map publishes no update, so this frame is the retry.
    session.requestFrame()
    ready
  } catch (rearmError: Throwable) {
    if (rearmError is VirtualMachineError) throw rearmError
    DesktopMapSurfaceState.Failed(
      "Desktop map renderer failed to take the surface back after frame $frameId",
      rearmError,
    )
  }
}

private sealed interface HostCreation {
  data class Created(val host: DesktopMapHost) : HostCreation

  data class Unavailable(val diagnostic: String) : HostCreation

  data class Failed(val diagnostic: String, val cause: Throwable?) : HostCreation
}

private fun createHost(
  runtimeBackends: Set<MapRenderBackend>,
  factory: DesktopMapHostFactory,
  rendererBackend: MapRenderBackend?,
): HostCreation {
  val selection =
    selectBackends(
      runtimeBackends = runtimeBackends,
      factory = factory,
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
      is DesktopMapHostResult.Created -> HostCreation.Created(result.host)
      is DesktopMapHostResult.Unsupported -> HostCreation.Unavailable(result.diagnostic)
      is DesktopMapHostResult.Failed -> HostCreation.Failed(result.diagnostic, result.cause)
    }
  } catch (error: Throwable) {
    if (error is VirtualMachineError) throw error
    HostCreation.Failed("${factory.description} threw while creating a desktop map host", error)
  }
}

private class DesktopMapDrawState {
  private var nextFrameId = 1L

  /**
   * The last target that was rendered into, redrawn when the renderer skips a frame.
   *
   * Deliberately survives a resize — one stretched frame beats blanking through a resize drag — at
   * the cost of a rule for hosts, recorded on [DesktopRenderTarget.generation]: a retired target
   * has to stay presentable until a newer one has been drawn.
   */
  var lastCompletedTarget: DesktopRenderTarget? = null

  /** Frames that failed since the last one that did not; consecutive rather than cumulative. */
  var frameFailures: Int = 0
    private set

  fun nextFrameId(): Long = nextFrameId++

  /** Counts a failed frame and reports which attempt at recovering it is. */
  fun recordFrameFailure(): Int = ++frameFailures

  fun onFrameSucceeded() {
    frameFailures = 0
  }

  fun reset() {
    lastCompletedTarget = null
    frameFailures = 0
  }
}

private class DesktopMapHostSessionImpl(
  private val host: DesktopMapHost,
  private val onRequestFrame: () -> Unit,
) : DesktopMapHostSession {
  override val backends: DesktopBackendPair
    get() = host.backends

  override fun requestFrame() {
    onRequestFrame()
  }

  override fun <T> withRendererAccess(action: () -> T): T = host.withRendererAccess(action)
}
