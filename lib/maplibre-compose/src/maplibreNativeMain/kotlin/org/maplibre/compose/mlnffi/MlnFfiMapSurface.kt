package org.maplibre.compose.mlnffi

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.util.rethrowIfFatal

/** The origin the frame clock counts from, fixed for the process so hosts can compare frames. */
private val frameClockOrigin = TimeSource.Monotonic.markNow()

/** Hosts [renderer] on a Compose drawing surface, driving the frame loop. */
@Composable
internal fun MlnFfiMapSurface(
  renderer: MlnFfiMapRenderer,
  hostResult: MlnFfiMapHostResult,
  modifier: Modifier = Modifier,
  logger: MapLog? = null,
  presentFrames: Boolean = true,
) {
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
          rethrowIfFatal(error)
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

  Canvas(modifier = modifier) {
    // Load-bearing read: it is what makes requestFrame() reschedule this Canvas.
    frameRequest
    // The draw scope supplies the current physical size and density. Use one extent for surface
    // configuration, rendering, and presentation.
    val frameExtent =
      MapExtent.fromPhysical(
        physicalWidth = size.width.roundToInt(),
        physicalHeight = size.height.roundToInt(),
        scaleFactor = this.density.toDouble(),
      )

    var drew = false
    if (presentFrames && host != null && session != null && !frameExtent.isEmpty && !failed) {
      val frameId = drawState.nextFrameId()
      val nowNanos = frameClockOrigin.elapsedNow().inWholeNanoseconds
      try {
        if (drawState.configuredExtent != frameExtent) {
          host.resize(frameExtent)
          renderer.onSurfaceChanged(frameExtent)
          drawState.configuredExtent = frameExtent
          session.requestFrame()
        }

        fun presentLastCompletedTarget() {
          val completed = drawState.lastCompletedPresentation ?: return
          val destinationAnchor = drawState.presentationAnchor(frameExtent)
          val destination =
            presentationDestination(
              extent = completed.target.extent,
              sourceAnchor = completed.anchor,
              destinationAnchor = destinationAnchor,
            )
          drew = host.draw(this, completed.target, destination)
        }

        when (val acquisition = host.acquireFrame(frameId, frameExtent, nowNanos)) {
          MlnFfiMapFrameAcquisition.NotReady -> {
            session.requestFrame()
            presentLastCompletedTarget()
          }
          is MlnFfiMapFrameAcquisition.Acquired -> {
            val frame = acquisition.frame
            var rendered = false
            try {
              val (result, anchor) =
                host.withProducerAccess(frame) {
                  renderer.render(frame) to renderer.presentationAnchor(frame.extent)
                }
              drawState.recordPresentationAnchor(frame.extent, anchor)
              when (result) {
                MlnFfiFrameResult.RENDERED -> {
                  host.completeProducerAccess(frame)
                  drawState.lastCompletedPresentation =
                    MlnFfiMapCompletedPresentation(frame.target, anchor)
                  rendered = true
                }
                MlnFfiFrameResult.SKIPPED -> Unit
              }
              presentLastCompletedTarget()
            } finally {
              runCatching { host.releaseFrame(frame) }
                .onFailure { logger?.e(it) { "Map host failed to release frame $frameId" } }
            }
            if (rendered) drawState.onFrameSucceeded()
          }
        }
      } catch (error: Throwable) {
        rethrowIfFatal(error)
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
  logger: MapLog?,
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
  drawState.lastCompletedPresentation = null
  try {
    renderer.onSurfaceLost()
  } catch (releaseError: Throwable) {
    rethrowIfFatal(releaseError)
    logger?.e(releaseError) { "Map renderer failed to release the lost surface" }
    return false
  }

  return try {
    renderer.onSurfaceAvailable(session)
    session.requestFrame()
    true
  } catch (rearmError: Throwable) {
    rethrowIfFatal(rearmError)
    logger?.e(rearmError) { "Map renderer failed to take the surface back after frame $frameId" }
    false
  }
}

private class MlnFfiMapDrawState {
  private var nextFrameId = 1L
  private var rendererClosed = false

  var lastCompletedPresentation: MlnFfiMapCompletedPresentation? = null
  var configuredExtent: MapExtent = MapExtent.Empty
  private var presentationExtent: MapExtent = MapExtent.Empty
  private var currentPresentationAnchor: MlnFfiMapPresentationAnchor? = null

  var frameFailures: Int = 0
    private set

  fun nextFrameId(): Long = nextFrameId++

  fun recordFrameFailure(): Int = ++frameFailures

  fun onFrameSucceeded() {
    frameFailures = 0
  }

  fun closeRenderer(renderer: MlnFfiMapRenderer, logger: MapLog?) {
    if (rendererClosed) return
    rendererClosed = true
    runCatching { renderer.close() }.onFailure { logger?.e(it) { "Map renderer failed to close" } }
  }

  fun reset() {
    lastCompletedPresentation = null
    configuredExtent = MapExtent.Empty
    presentationExtent = MapExtent.Empty
    currentPresentationAnchor = null
    frameFailures = 0
    rendererClosed = false
  }

  fun recordPresentationAnchor(extent: MapExtent, anchor: MlnFfiMapPresentationAnchor) {
    presentationExtent = extent
    currentPresentationAnchor = anchor
  }

  fun presentationAnchor(extent: MapExtent): MlnFfiMapPresentationAnchor =
    currentPresentationAnchor?.takeIf { presentationExtent == extent }
      ?: extent.centerPresentationAnchor()
}

private data class MlnFfiMapCompletedPresentation(
  val target: MlnFfiRenderTarget,
  val anchor: MlnFfiMapPresentationAnchor,
)

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

  override fun enqueueRenderer(action: () -> Unit): Boolean = host.enqueueRenderer(action)
}
