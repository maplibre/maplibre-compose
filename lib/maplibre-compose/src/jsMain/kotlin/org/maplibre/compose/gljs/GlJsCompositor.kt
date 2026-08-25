package org.maplibre.compose.gljs

import androidx.compose.runtime.staticCompositionLocalOf
import co.touchlab.kermit.Logger
import org.maplibre.compose.map.MapExtent
import web.html.HTMLCanvasElement

/** What a map should render into for one frame. */
internal sealed interface GlJsFrameTarget {
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

/** A seam for tests that run against a raster surface with no WebGL context. */
internal val LocalGlJsCompositor =
  staticCompositionLocalOf<(Logger?) -> GlJsCompositor> {
    { logger -> ComposeGlJsCompositor(logger) }
  }

/** A target is never resized in place: WebGL cannot resize a texture. */
internal class ComposeGlJsCompositor(private val logger: Logger?) : GlJsCompositor {

  private var canvas: HTMLCanvasElement? = null
  private var target: GlJsRenderTarget? = null
  private var generation = 0L
  private var reportedUnavailable = false

  /**
   * Null before Compose has built its own. Re-read each acquire so a canvas Compose replaced during
   * a resize is picked up.
   */
  private fun context(): dynamic {
    bind(EmscriptenGl.skikoCanvas() ?: canvas)
    val resolved = canvas?.let { EmscriptenGl.contextOf(it) }
    if (resolved == null) bind(null)
    return resolved
  }

  private fun bind(next: HTMLCanvasElement?) {
    if (next === canvas) return
    dropTarget()
    canvas = next
  }

  override fun acquire(extent: MapExtent): GlJsFrameTarget {
    if (extent.isEmpty) return GlJsFrameTarget.NotReady
    val context = context()
    val current = target
    if (
      current != null &&
        current.widthPx == extent.physicalWidth &&
        current.heightPx == extent.physicalHeight
    ) {
      return GlJsFrameTarget.Composited(current)
    }
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
    dropTarget()
    target = next
    return GlJsFrameTarget.Composited(next)
  }

  private fun dropTarget() {
    val current = target ?: return
    target = null
    runCatching { current.close() }
  }

  override fun close() {
    dropTarget()
  }
}

/** A compositor for a Compose surface that has no GPU behind it. See [GlJsFrameTarget.Detached]. */
internal class DetachedGlJsCompositor : GlJsCompositor {
  override fun acquire(extent: MapExtent): GlJsFrameTarget = GlJsFrameTarget.Detached

  override fun close() = Unit
}
