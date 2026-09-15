package org.maplibre.compose.gljs

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.channels.Channel
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.MapExtent

/** Hosts [renderer] on a Compose drawing surface. Compose owns the frame loop. */
@Composable
internal fun GlJsMapSurface(
  renderer: GlJsMapRenderer,
  modifier: Modifier,
  logger: MapLog?,
  presentFrames: Boolean,
) {
  val density = LocalDensity.current.density.toDouble()
  var frameRequest by remember { mutableLongStateOf(0L) }
  var failed by remember(renderer) { mutableStateOf(false) }
  val createCompositor = LocalGlJsCompositor.current
  val compositor = remember(renderer, createCompositor) { createCompositor(logger) }
  val requests = remember(renderer, compositor) { Channel<Unit>(Channel.CONFLATED) }
  // The image object stays the same when MapLibre updates its texture. Each rendered frame must
  // still invalidate draw, so assigning the same target is an observable change.
  var prepared by
    remember(renderer, compositor) {
      mutableStateOf<GlJsRenderTarget?>(null, neverEqualPolicy())
    }
  val preparation = remember(renderer, compositor) { GlJsFramePreparation() }
  val surface =
    remember(compositor) {
      object : GlJsSurfaceSession {
        override fun requestFrame() {
          requests.trySend(Unit)
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
      prepared = null
      requests.close()
      compositor.close()
    }
  }

  // Coalesce repaint requests and defer requests made during rendering to the next Compose frame.
  // Idle maps suspend here instead of continuously invalidating placement.
  LaunchedEffect(requests) {
    for (request in requests) {
      withFrameNanos {
        // The first request woke the receiver; include requests received while waiting too.
        requests.tryReceive()
        frameRequest += 1
      }
    }
  }

  Canvas(
    modifier =
      modifier.layout { measurable, constraints ->
        val child = measurable.measure(constraints)
        val extent = MapExtent.fromPhysical(child.width, child.height, density)
        layout(child.width, child.height) {
          val request = frameRequest
          // The map is the first child of MaplibreMap's Box, before its overlay. Prepare the
          // texture and projection here, with the measured extent, before geographic placement.
          Snapshot.withoutReadObservation {
            if (!failed && !extent.isEmpty && preparation.needsFrame(request, extent)) {
              try {
                val acquired = compositor.acquire(extent)
                val rendered =
                  acquired != GlJsFrameTarget.UnsupportedSize && renderer.render(acquired, extent)
                when (acquired) {
                  GlJsFrameTarget.NotReady -> surface.requestFrame()
                  GlJsFrameTarget.Detached,
                  GlJsFrameTarget.UnsupportedSize -> prepared = null
                  is GlJsFrameTarget.Composited -> {
                    if (rendered) prepared = acquired.target
                    else if (prepared !== acquired.target) prepared = null
                  }
                }
              } catch (error: Throwable) {
                failed = true
                prepared = null
                logger?.e(error) {
                  "The map failed while preparing a frame and will not be drawn again"
                }
                runCatching { renderer.close() }
                  .onFailure { logger?.e(it) { "The map failed to close after a render failure" } }
              }
            }
          }
          child.place(0, 0)
        }
      }
  ) {
    val target = prepared
    // A host-canvas resize can replace Skia's renderer even when the map keeps the same extent.
    // Never submit a texture adopted by the retired context to its replacement.
    val currentTarget =
      target != null && EmscriptenGl.currentContext()?.handle == target.hostContext.handle
    if (target != null && !currentTarget) surface.requestFrame()
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
    } else {
      drawRect(Color.Transparent, size = Size(size.width, size.height))
    }
  }
}

private class GlJsFramePreparation {
  private var request = -1L
  private var extent = MapExtent.Empty

  fun needsFrame(nextRequest: Long, nextExtent: MapExtent): Boolean {
    if (request == nextRequest && extent == nextExtent) return false
    request = nextRequest
    extent = nextExtent
    return true
  }
}
