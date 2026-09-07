package org.maplibre.compose.gljs

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
  var physicalSize by remember { mutableStateOf(IntSize.Zero) }
  val compositor = rememberGlJsCompositor(physicalSize)
  val controller =
    remember(renderer, compositor) {
      GlJsSurfaceController(renderer, compositor, logger)
    }
  // Node detachment can be temporary; composition owns the platform resources.
  DisposableEffect(controller) { onDispose { controller.close() } }
  Box(modifier.onSizeChanged { physicalSize = it }.mapSurface(controller, presentFrames))
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
      var rendered = false
      val acquired = compositor.render(extent) { target ->
        rendered = renderer.render(target, extent)
      }
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
    with(scope) {
      val painter = compositor.painter
      if (presentFrames && target != null && painter != null) {
        with(painter) { draw(size) }
      } else drawRect(Color.Transparent)
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    wake = {}
    runCatching { renderer.onSurfaceLost(this) }
      .onFailure { logger?.e(it) { "The map failed to release its surface" } }
    prepared = null
    compositor.close()
  }
}
