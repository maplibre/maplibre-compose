package org.maplibre.compose.gljs

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import co.touchlab.kermit.Logger
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
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
  var recoveryEpoch by remember(renderer) { mutableIntStateOf(0) }
  var consecutiveFailures by remember(renderer) { mutableIntStateOf(0) }
  val createCompositor = LocalGlJsCompositor.current
  val compositor = remember(renderer, createCompositor, recoveryEpoch) { createCompositor(logger) }
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
      // Before the compositor frees the target these point at.
      runCatching { renderer.onSurfaceLost() }
        .onFailure { logger?.e(it) { "The map failed to release its surface" } }
      compositor.close()
    }
  }

  // presentFrames is a key so that its flip to true redraws the frame the draw pass below
  // declined to blit.
  LaunchedEffect(extent, renderer, failed, presentFrames) {
    if (extent.isEmpty || failed) return@LaunchedEffect
    surface.requestFrame()
  }

  Canvas(modifier = modifier.onSizeChanged { physicalSize = it }) {
    // Load-bearing read: it is what makes requestFrame() reschedule this Canvas.
    frameRequest

    var drew = false
    if (!extent.isEmpty && !failed) {
      try {
        val acquired = compositor.acquire(extent)
        renderer.render(acquired, extent)
        when (acquired) {
          GlJsFrameTarget.NotReady -> surface.requestFrame()
          GlJsFrameTarget.Detached -> Unit
          is GlJsFrameTarget.Composited -> {
            if (presentFrames) {
              val target = acquired.target
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
              drew = true
            }
            // An unstyled framebuffer is black, so no blit before the first style; the map
            // requests its own repaints.
          }
        }
        consecutiveFailures = 0
      } catch (error: Throwable) {
        consecutiveFailures += 1
        if (consecutiveFailures > MAX_FRAME_RECOVERY_ATTEMPTS) {
          failed = true
          logger?.e(error) { "The map failed while rendering a frame and will not be drawn again" }
          runCatching { renderer.close() }
            .onFailure { logger?.e(it) { "The map failed to close after a render failure" } }
        } else {
          logger?.w(error) {
            "The map failed while rendering a frame; rebuilding the render session " +
              "(attempt $consecutiveFailures of $MAX_FRAME_RECOVERY_ATTEMPTS)"
          }
          recoveryEpoch += 1
          surface.requestFrame()
        }
      }
    }

    if (!drew) drawRect(Color.Transparent, size = Size(size.width, size.height))
  }
}

private const val MAX_FRAME_RECOVERY_ATTEMPTS = 3
