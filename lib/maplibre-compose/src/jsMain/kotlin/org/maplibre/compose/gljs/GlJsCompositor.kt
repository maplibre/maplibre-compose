package org.maplibre.compose.gljs

import androidx.compose.runtime.staticCompositionLocalOf
import co.touchlab.kermit.Logger
import org.maplibre.compose.map.MapExtent
import web.html.HTMLCanvasElement

/** What a map should render into for one frame. */
internal sealed interface GlJsFrameTarget {
  /** Render into this, on the context Compose owns. */
  data class Composited(val target: GlJsRenderTarget) : GlJsFrameTarget

  /** Compose has not finished building the renderer whose context the map shares. */
  data object NotReady : GlJsFrameTarget

  /** A Compose surface with no GPU behind it: the map runs on a canvas nothing samples. */
  data object Detached : GlJsFrameTarget
}

/** Supplies the render target for one map, frame by frame. */
internal interface GlJsCompositor : AutoCloseable {
  fun acquire(extent: MapExtent): GlJsFrameTarget
}

/** A seam for the conformance suite, which runs against a raster surface with no WebGL context. */
internal val LocalGlJsCompositor =
  staticCompositionLocalOf<(Logger?) -> GlJsCompositor> {
    { logger -> ComposeGlJsCompositor(logger) }
  }

/**
 * A target is never resized in place: WebGL cannot resize a texture, and Skia has adopted the one
 * behind the old target anyway. Releasing the previous one drops only this platform's reference,
 * since a Compose graphics layer may still hold a display list that draws it.
 */
internal class ComposeGlJsCompositor(private val logger: Logger?) : GlJsCompositor {

  private var canvas: HTMLCanvasElement? = null
  private var gl: dynamic = null
  private var target: GlJsRenderTarget? = null
  private var generation = 0L
  private var reportedUnavailable = false

  /** Null before Compose has built its own. */
  private fun context(): dynamic {
    if (gl != null) return gl
    val found = EmscriptenGl.skikoCanvas() ?: return null
    val context = EmscriptenGl.contextOf(found) ?: return null
    canvas = found
    gl = context
    return context
  }

  override fun acquire(extent: MapExtent): GlJsFrameTarget {
    if (extent.isEmpty) return GlJsFrameTarget.NotReady
    val current = target
    if (
      current != null &&
        current.widthPx == extent.physicalWidth &&
        current.heightPx == extent.physicalHeight
    ) {
      return GlJsFrameTarget.Composited(current)
    }

    val context = context()
    if (context == null || !SkikoGpuBridge.isReady) {
      if (!reportedUnavailable) {
        reportedUnavailable = true
        logger?.d {
          "Waiting to composite the map: " +
            if (context == null) "Compose has no WebGL context yet" else SkikoGpuBridge.diagnostic()
        }
      }
      return GlJsFrameTarget.NotReady
    }
    reportedUnavailable = false

    val next =
      GlJsRenderTarget(
        gl = context,
        widthPx = extent.physicalWidth,
        heightPx = extent.physicalHeight,
        generation = ++generation,
      )
    target = next
    current?.close()
    return GlJsFrameTarget.Composited(next)
  }

  override fun close() {
    target?.close()
    target = null
  }
}

/** A compositor for a Compose surface that has no GPU behind it. See [GlJsFrameTarget.Detached]. */
internal class DetachedGlJsCompositor : GlJsCompositor {
  override fun acquire(extent: MapExtent): GlJsFrameTarget = GlJsFrameTarget.Detached

  override fun close() = Unit
}
