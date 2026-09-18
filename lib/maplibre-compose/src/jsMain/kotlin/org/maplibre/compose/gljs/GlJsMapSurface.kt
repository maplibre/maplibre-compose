package org.maplibre.compose.gljs

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.ComposeMapSurface
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.mapSurface

/** Uses the same frame scheduler and preparation boundary as native Compose texture surfaces. */
@Composable
internal fun GlJsMapSurface(
  renderer: GlJsMapRenderer,
  modifier: Modifier,
  logger: MapLog?,
  presentFrames: Boolean,
) {
  val createCompositor = LocalGlJsCompositor.current
  val controller =
    remember(renderer, createCompositor) {
      GlJsSurfaceController(renderer, createCompositor(logger), logger)
    }
  // Node detachment can be temporary; composition owns the platform resources.
  DisposableEffect(controller) { onDispose { controller.close() } }
  Box(modifier.mapSurface(controller, presentFrames))
}

private class GlJsSurfaceController(
  private val renderer: GlJsMapRenderer,
  private val compositor: GlJsCompositor,
  private val logger: MapLog?,
) : ComposeMapSurface, GlJsSurfaceSession {
  private var wake: () -> Unit = {}
  private var prepared: GlJsRenderTarget? = null
  private var failed = false
  private var closed = false
  private var attached = false
  private var presentFrames = true
  override val maximumFps: Int?
    get() = renderer.maximumFps

  override fun setPresentFrames(value: Boolean) {
    if (presentFrames == value) return
    presentFrames = value
    requestFrame()
  }

  override fun attach(requestFrame: () -> Unit) {
    wake = requestFrame
    if (!attached) {
      attached = true
      renderer.onSurfaceAvailable(this)
    }
    requestFrame()
  }

  override fun detach() {
    wake = {}
  }

  override fun requestFrame() {
    if (!closed) wake()
  }

  override fun prepare(extent: MapExtent): Boolean {
    if (failed || closed || extent.isEmpty) return false
    return try {
      val acquired = compositor.acquire(extent)
      val rendered =
        acquired != GlJsFrameTarget.UnsupportedSize && renderer.render(acquired, extent)
      when (acquired) {
        GlJsFrameTarget.NotReady -> requestFrame()
        GlJsFrameTarget.OwnCanvas,
        GlJsFrameTarget.UnsupportedSize -> prepared = null
        is GlJsFrameTarget.Composited -> {
          if (rendered) prepared = acquired.target
          else if (prepared !== acquired.target) prepared = null
        }
      }
      rendered
    } catch (error: Throwable) {
      failed = true
      prepared = null
      logger?.e(error) { "The map failed while preparing a frame" }
      runCatching { renderer.close() }.onFailure { logger?.e(it) { "The map failed to close" } }
      false
    }
  }

  override fun present(extent: MapExtent) {
    renderer.presentFrame(prepared?.takeIf { presentFrames }, extent)
  }

  override fun draw(scope: DrawScope) {
    val target = prepared
    val currentTarget =
      target != null && EmscriptenGl.currentContext()?.handle == target.hostContext.handle
    if (target != null && !currentTarget) requestFrame()
    with(scope) {
      if (presentFrames && currentTarget) {
        drawIntoCanvas { canvas ->
          canvas.skiaCanvas.drawImageRect(
            image = target.image,
            src = Rect.makeWH(target.widthPx.toFloat(), target.heightPx.toFloat()),
            dst = Rect.makeWH(size.width, size.height),
            samplingMode = SamplingMode.LINEAR,
            paint = null,
            strict = true,
          )
        }
      } else drawRect(Color.Transparent)
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    wake = {}
    runCatching { renderer.onSurfaceLost() }
      .onFailure { logger?.e(it) { "The map failed to release its surface" } }
    prepared = null
    compositor.close()
  }
}
