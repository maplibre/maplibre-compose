package org.maplibre.compose.gljs

import androidx.compose.runtime.staticCompositionLocalOf
import co.touchlab.kermit.Logger
import org.maplibre.compose.map.MapExtent

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

  private var target: GlJsRenderTarget? = null
  private var generation = 0L
  private var reportedUnavailable = false

  override fun acquire(extent: MapExtent): GlJsFrameTarget {
    if (extent.isEmpty) return GlJsFrameTarget.NotReady
    val hostContext = EmscriptenGl.currentContext()
    if (hostContext == null || !SkikoGpuBridge.isReady(hostContext)) {
      if (!reportedUnavailable) {
        reportedUnavailable = true
        logger?.d {
          "Waiting to composite the map: " +
            if (hostContext == null) "Compose has no current WebGL renderer"
            else SkikoGpuBridge.diagnostic(hostContext)
        }
      }
      return GlJsFrameTarget.NotReady
    }
    reportedUnavailable = false

    val current = target
    if (
      current != null &&
        current.hostContext.handle == hostContext.handle &&
        current.widthPx == extent.physicalWidth &&
        current.heightPx == extent.physicalHeight
    ) {
      return GlJsFrameTarget.Composited(current)
    }

    val next =
      GlJsRenderTarget(
        hostContext = hostContext,
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
