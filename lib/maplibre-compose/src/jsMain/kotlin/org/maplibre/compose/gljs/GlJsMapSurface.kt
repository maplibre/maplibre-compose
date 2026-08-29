package org.maplibre.compose.gljs

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import co.touchlab.kermit.Logger
import org.maplibre.compose.map.MapExtent

/** Hosts [renderer] on a Compose drawing surface. Compose owns the frame loop. */
@Composable
internal fun GlJsMapSurface(
  renderer: GlJsMapRenderer,
  modifier: Modifier,
  logger: Logger?,
  presentFrames: Boolean,
) {
  val density = LocalDensity.current.density.toDouble()
  var physicalSize by remember { mutableStateOf(IntSize.Zero) }
  val extent =
    remember(physicalSize, density) {
      MapExtent.fromPhysical(physicalSize.width, physicalSize.height, density)
    }
  var frameRequest by remember { mutableLongStateOf(0L) }
  var failed by remember(renderer) { mutableStateOf(false) }
  val compositor = rememberGlJsCompositor(physicalSize)
  val surface =
    remember(compositor) {
      object : GlJsSurfaceSession {
        override fun requestFrame() {
          frameRequest += 1
        }
      }
    }

  DisposableEffect(renderer, compositor) {
    renderer.onSurfaceAvailable(surface)
    surface.requestFrame()
    onDispose {
      runCatching { renderer.onSurfaceLost() }
        .onFailure { logger?.e(it) { "The map failed to release its surface" } }
      compositor.close()
    }
  }

  LaunchedEffect(extent, renderer, compositor, failed, frameRequest) {
    if (extent.isEmpty || failed) return@LaunchedEffect
    withFrameNanos {
      try {
        when (compositor.render(extent) { target -> renderer.render(target, extent) }) {
          GlJsFrameTarget.NotReady -> surface.requestFrame()
          GlJsFrameTarget.Detached,
          is GlJsFrameTarget.Composited -> Unit
        }
      } catch (error: Throwable) {
        failed = true
        logger?.e(error) { "The map failed while rendering a frame and will not be drawn again" }
        runCatching { renderer.close() }
          .onFailure { logger?.e(it) { "The map failed to close after a render failure" } }
      }
    }
  }

  Canvas(modifier = modifier.onSizeChanged { physicalSize = it }) {
    frameRequest
    drawRect(Color.Transparent, size = Size(size.width, size.height))
    if (presentFrames) {
      compositor.painter?.let { painter -> with(painter) { draw(size) } }
    }
  }
}
