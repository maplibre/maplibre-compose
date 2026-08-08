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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import co.touchlab.kermit.Logger

/** Hosts [renderer] on a Compose drawing surface, driving the frame loop. */
@Composable
internal fun MlnFfiMapSurface(
  renderer: MlnFfiMapRenderer,
  hostResult: MlnFfiMapHostResult,
  modifier: Modifier = Modifier,
  logger: Logger? = null,
) {
  val density = LocalDensity.current.density.toDouble()
  var physicalSize by remember { mutableStateOf(IntSize.Zero) }
  val extent =
    remember(physicalSize, density) {
      MlnFfiMapExtent.fromPhysical(physicalSize.width, physicalSize.height, density)
    }
  var frameRequest by remember { mutableLongStateOf(0L) }
  var failed by remember(renderer, hostResult) { mutableStateOf(false) }
  val drawState = remember(renderer, hostResult) { MlnFfiMapDrawState() }
  val host = (hostResult as? MlnFfiMapHostResult.Created)?.host
  val session = remember(host) { host?.let { MlnFfiMapHostSessionImpl(it) { frameRequest += 1 } } }

  DisposableEffect(hostResult, renderer, session) {
    when (hostResult) {
      is MlnFfiMapHostResult.Created -> {
        checkNotNull(session)
        try {
          renderer.onSurfaceAvailable(session)
          session.requestFrame()
        } catch (error: Throwable) {
          if (error is VirtualMachineError) throw error
          failed = true
          logger?.e(error) { "Map renderer failed to take the host surface" }
          drawState.closeRenderer(renderer, logger)
        }
      }
      is MlnFfiMapHostResult.Failed -> {
        failed = true
        logger?.e(hostResult.cause) { hostResult.diagnostic }
        drawState.closeRenderer(renderer, logger)
      }
    }

    onDispose {
      if (host != null) {
        // Drop render-session references before the host frees its targets.
        runCatching { renderer.onSurfaceLost() }
          .onFailure { logger?.e(it) { "Map renderer failed to release the host surface" } }
        runCatching { host.close() }.onFailure { logger?.e(it) { "Map host failed to close" } }
      }
      drawState.reset()
    }
  }

  LaunchedEffect(extent, host, renderer, failed) {
    if (host == null || extent.isEmpty || failed) return@LaunchedEffect
    try {
      host.resize(extent)
      renderer.onSurfaceChanged(extent)
      session?.requestFrame()
    } catch (error: Throwable) {
      if (error is VirtualMachineError) throw error
      failed = true
      logger?.e(error) { "Map host failed to resize to ${extent.width}x${extent.height}" }
      drawState.closeRenderer(renderer, logger)
    }
  }

  Canvas(modifier = modifier.onSizeChanged { physicalSize = it }) {
    // Load-bearing read: it is what makes requestFrame() reschedule this Canvas.
    frameRequest

    var drew = false
    if (host != null && session != null && !extent.isEmpty && !failed) {
      val frameId = drawState.nextFrameId()
      try {
        when (val acquisition = host.acquireFrame(frameId, extent, System.nanoTime())) {
          MlnFfiMapFrameAcquisition.NotReady -> session.requestFrame()
          is MlnFfiMapFrameAcquisition.Acquired -> {
            val frame = acquisition.frame
            var rendered = false
            try {
              when (host.withProducerAccess(frame) { renderer.render(frame) }) {
                MlnFfiFrameResult.RENDERED -> {
                  host.completeProducerAccess(frame)
                  drawState.lastCompletedTarget = frame.target
                  rendered = true
                }
                MlnFfiFrameResult.SKIPPED -> Unit
              }
              drawState.lastCompletedTarget?.let { drew = host.draw(this, it) }
            } finally {
              runCatching { host.releaseFrame(frame) }
                .onFailure { logger?.e(it) { "Map host failed to release frame $frameId" } }
            }
            if (rendered) drawState.onFrameSucceeded()
          }
        }
      } catch (error: Throwable) {
        if (error is VirtualMachineError) throw error
        if (!recoverFromFrameFailure(renderer, session, drawState, frameId, error, logger)) {
          failed = true
          drawState.closeRenderer(renderer, logger)
        }
      }
    }

    if (!drew) drawRect(Color.Transparent, size = Size(size.width, size.height))
  }
}

private const val MAX_FRAME_RECOVERY_ATTEMPTS = 3

/** Rebuilds after an explicitly recoverable graphics failure, or reports that recovery is done. */
private fun recoverFromFrameFailure(
  renderer: MlnFfiMapRenderer,
  session: MlnFfiMapHostSession,
  drawState: MlnFfiMapDrawState,
  frameId: Long,
  error: Throwable,
  logger: Logger?,
): Boolean {
  if (error !is MlnFfiRecoverableFrameException) {
    logger?.e(error) { "Map frame $frameId failed with an unrecoverable error" }
    return false
  }

  val attempt = drawState.recordFrameFailure()
  if (attempt > MAX_FRAME_RECOVERY_ATTEMPTS) {
    logger?.e(error) {
      "Map frame $frameId failed after $MAX_FRAME_RECOVERY_ATTEMPTS recovery attempts"
    }
    return false
  }

  logger?.w(error) {
    "Map frame $frameId failed; rebuilding the render session " +
      "(attempt $attempt of $MAX_FRAME_RECOVERY_ATTEMPTS)"
  }
  drawState.lastCompletedTarget = null
  runCatching { renderer.onSurfaceLost() }
    .onFailure { logger?.e(it) { "Map renderer failed to release the lost surface" } }

  return try {
    renderer.onSurfaceAvailable(session)
    session.requestFrame()
    true
  } catch (rearmError: Throwable) {
    if (rearmError is VirtualMachineError) throw rearmError
    logger?.e(rearmError) { "Map renderer failed to take the surface back after frame $frameId" }
    false
  }
}

private class MlnFfiMapDrawState {
  private var nextFrameId = 1L
  private var rendererClosed = false

  var lastCompletedTarget: MlnFfiRenderTarget? = null

  var frameFailures: Int = 0
    private set

  fun nextFrameId(): Long = nextFrameId++

  fun recordFrameFailure(): Int = ++frameFailures

  fun onFrameSucceeded() {
    frameFailures = 0
  }

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
