package org.maplibre.compose.mlnffi

import org.maplibre.compose.map.MapExtent

/**
 * An in-memory [MlnFfiMapRenderer] that records surface lifecycle and frames without MapLibre.
 *
 * [onSurfaceAvailable] wraps the host session in [RecordingMlnFfiMapHostSession] so a test can
 * count [MlnFfiMapHostSession.requestFrame] from the renderer.
 */
internal class RecordingMlnFfiMapRenderer(
  private var failingRenders: Int = 0,
  private val unexpectedFailure: Boolean = false,
  private val requestAnotherFrame: Boolean = false,
  private val renderResults: ArrayDeque<MlnFfiFrameResult> = ArrayDeque(),
  private var additionalFrameRequests: Int = 0,
  private var failingSurfaceLosses: Int = 0,
) : MlnFfiMapRenderer {
  override val backend: MapRenderBackend = MapRenderBackend.VULKAN
  val lifecycle: MutableList<String> = mutableListOf()
  val renderTargets: MutableList<MlnFfiRenderTarget> = mutableListOf()
  val surfaceChanges: MutableList<MapExtent> = mutableListOf()
  val surfaceExtentAtRenders: MutableList<MapExtent?> = mutableListOf()
  var renderedFrames = 0
    private set

  var surfaceLostCount = 0
    private set

  var closeCount = 0
    private set

  var failingSurfaceChanges = 0
  var skipNextRender = false
  var skipAllRenders = false
  var presentationAnchorOffsetX = 0
  var presentationAnchorOffsetY = 0
  var skippedFrames = 0
    private set

  var hostSession: RecordingMlnFfiMapHostSession? = null
    private set

  override fun onSurfaceChanged(extent: MapExtent) {
    if (failingSurfaceChanges > 0) {
      failingSurfaceChanges--
      throw IllegalStateException("cannot resize to ${extent.width}x${extent.height}")
    }
    surfaceChanges += extent
  }

  override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
    lifecycle += "onSurfaceAvailable"
    hostSession = RecordingMlnFfiMapHostSession(session)
  }

  override fun onSurfaceLost() {
    surfaceLostCount++
    lifecycle += "onSurfaceLost"
    if (failingSurfaceLosses > 0) {
      failingSurfaceLosses--
      throw IllegalStateException("deliberate surface loss failure")
    }
  }

  override fun render(frame: MlnFfiMapFrame): MlnFfiFrameResult {
    if (failingRenders > 0) {
      failingRenders--
      val error = "renderer lost its device on frame ${frame.frameId}"
      throw if (unexpectedFailure) IllegalStateException(error)
      else MlnFfiRecoverableFrameException(error, null)
    }
    renderedFrames++
    renderTargets += frame.target
    surfaceExtentAtRenders += surfaceChanges.lastOrNull()
    if (requestAnotherFrame || additionalFrameRequests > 0) {
      if (additionalFrameRequests > 0) additionalFrameRequests--
      hostSession?.requestFrame()
    }
    if (skipNextRender || skipAllRenders) {
      skipNextRender = false
      skippedFrames++
      return MlnFfiFrameResult.SKIPPED
    }
    return renderResults.removeFirstOrNull() ?: MlnFfiFrameResult.RENDERED
  }

  override fun presentationAnchor(extent: MapExtent): MlnFfiMapPresentationAnchor {
    val center = extent.centerPresentationAnchor()
    return MlnFfiMapPresentationAnchor(
      x = center.x + presentationAnchorOffsetX,
      y = center.y + presentationAnchorOffsetY,
    )
  }

  override fun close() {
    closeCount++
  }
}
