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
  data class Ready(val backends: DesktopBackendPair, val capabilities: DesktopHostCapabilities) :
    DesktopMapSurfaceState

  /** No usable backend, or the host factory declined. Not an error the application can retry. */
  data class Unavailable(val diagnostic: String) : DesktopMapSurfaceState

  /** A host existed but something went wrong. */
  data class Failed(val diagnostic: String, val cause: Throwable? = null) : DesktopMapSurfaceState
}

/**
 * Hosts [renderer] on a Compose drawing surface, driving the frame loop.
 *
 * Owns the surface lifecycle only. What gets rendered, and when a frame is worth requesting,
 * belongs to the renderer.
 */
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

  // Recreating the host on a factory or backend change is intentional: the GPU objects on both
  // sides of the bridge belong to the host, so they cannot outlive it.
  val hostResult =
    remember(factory, runtimeBackends, renderer) {
      createHost(runtimeBackends, factory, renderer.backend)
    }
  val host = (hostResult as? HostCreation.Created)?.host
  val session = remember(host) { host?.let { DesktopMapHostSessionImpl(it) { frameRequest += 1 } } }

  LaunchedEffect(state) { onStateChanged(state) }

  DisposableEffect(host, hostResult, session, renderer) {
    state =
      when (hostResult) {
        is HostCreation.Created -> {
          checkNotNull(session)
          renderer.onSurfaceAvailable(session)
          session.requestFrame()
          DesktopMapSurfaceState.Ready(hostResult.host.backends, hostResult.host.capabilities)
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
        // Order matters: the renderer must drop its references to host-owned targets before the
        // host frees them.
        runCatching { renderer.onSurfaceLost() }
          .onFailure { logger?.e(it) { "Desktop map renderer failed to release its surface" } }
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
      drawState.onExtentChanged(extent)
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
    // Reading the frame request inside the draw scope is what makes a requestFrame() call
    // reschedule this Canvas.
    frameRequest

    var drew = false
    if (host != null && session != null && !extent.isEmpty) {
      val currentState = state
      if (currentState is DesktopMapSurfaceState.Ready) {
        val frameId = drawState.nextFrameId()
        val frame =
          try {
            host.acquireFrame(frameId, extent, System.nanoTime())
          } catch (error: Throwable) {
            state =
              DesktopMapSurfaceState.Failed(
                "Desktop map host failed to acquire frame $frameId",
                error,
              )
            null
          }

        if (frame != null) {
          try {
            when (host.withProducerAccess(frame) { renderer.render(frame) }) {
              DesktopFrameResult.RENDERED -> {
                host.completeProducerAccess(frame)
                drawState.lastCompletedTarget = frame.target
              }
              DesktopFrameResult.SKIPPED -> Unit
            }
            drawState.lastCompletedTarget?.let { drew = host.draw(this, it) }
          } catch (error: Throwable) {
            state =
              DesktopMapSurfaceState.Failed("Desktop map renderer failed on frame $frameId", error)
          } finally {
            runCatching { host.releaseFrame(frame) }
              .onFailure { logger?.e(it) { "Desktop map host failed to release frame $frameId" } }
          }
        }
      }
    }

    if (!drew) drawRect(Color.Transparent)
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
  private var extent = DesktopMapExtent.Empty
  private var nextFrameId = 1L

  /**
   * The last target that was rendered into, redrawn when the renderer skips a frame.
   *
   * Cleared on resize, since a target allocated at the old extent would be drawn stretched.
   */
  var lastCompletedTarget: DesktopRenderTarget? = null

  fun onExtentChanged(next: DesktopMapExtent) {
    if (next != extent) {
      extent = next
      lastCompletedTarget = null
    }
  }

  fun nextFrameId(): Long = nextFrameId++

  fun reset() {
    extent = DesktopMapExtent.Empty
    lastCompletedTarget = null
  }
}

private class DesktopMapHostSessionImpl(
  private val host: DesktopMapHost,
  private val onRequestFrame: () -> Unit,
) : DesktopMapHostSession {
  override val backends: DesktopBackendPair
    get() = host.backends

  override val capabilities: DesktopHostCapabilities
    get() = host.capabilities

  override fun requestFrame() {
    onRequestFrame()
  }

  override fun <T> withRendererAccess(action: () -> T): T = host.withRendererAccess(action)
}
