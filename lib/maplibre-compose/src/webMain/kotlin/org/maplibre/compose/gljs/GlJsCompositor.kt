package org.maplibre.compose.gljs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.webgl.WebGLRenderTarget
import androidx.compose.ui.platform.webgl.rememberWebGLRenderTarget
import androidx.compose.ui.unit.IntSize
import org.maplibre.compose.map.MapExtent

/** The target for one map frame. */
internal sealed interface GlJsFrameTarget {
  data class Composited(val target: GlJsRenderTarget) : GlJsFrameTarget

  /** Compose has not finished creating its GPU renderer. */
  data object NotReady : GlJsFrameTarget

  /** A Compose surface without a GPU renderer. The map renders into an unobserved canvas. */
  data object Detached : GlJsFrameTarget
}

/** Runs MapLibre frames and supplies the painter that presents them. */
internal interface GlJsCompositor : AutoCloseable {
  val painter: Painter?

  /** Runs [render] in the WebGL period that this compositor provides. */
  fun render(extent: MapExtent, renderMap: (GlJsFrameTarget) -> Unit): GlJsFrameTarget
}

internal typealias GlJsCompositorFactory = @Composable (size: IntSize) -> GlJsCompositor

/** Supplies a test compositor when a Compose surface has no WebGL context. */
internal val LocalGlJsCompositor = staticCompositionLocalOf<GlJsCompositorFactory?> { null }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun rememberGlJsCompositor(size: IntSize): GlJsCompositor {
  val factory = LocalGlJsCompositor.current
  if (factory != null) return factory(size)
  val target = rememberWebGLRenderTarget(size)
  return remember(target) {
    if (target == null) DetachedGlJsCompositor() else ComposeGlJsCompositor(target)
  }
}

/** Uses the WebGL target and lifecycle that Compose UI provides. */
@OptIn(ExperimentalComposeUiApi::class)
internal class ComposeGlJsCompositor(private val target: WebGLRenderTarget) : GlJsCompositor {
  private val mapTarget = ComposeGlJsRenderTarget(target)

  override val painter: Painter = target.painter

  override fun render(
    extent: MapExtent,
    renderMap: (GlJsFrameTarget) -> Unit,
  ): GlJsFrameTarget {
    if (extent.isEmpty) return GlJsFrameTarget.NotReady
    val frameTarget = GlJsFrameTarget.Composited(mapTarget)
    return if (target.render { renderMap(frameTarget) }) {
      frameTarget
    } else {
      GlJsFrameTarget.NotReady
    }
  }

  override fun close() {
    target.markGLStateStale()
  }
}

/** A compositor for a Compose surface without a GPU renderer. */
internal class DetachedGlJsCompositor : GlJsCompositor {
  override val painter: Painter? = null

  override fun render(
    extent: MapExtent,
    renderMap: (GlJsFrameTarget) -> Unit,
  ): GlJsFrameTarget {
    renderMap(GlJsFrameTarget.Detached)
    return GlJsFrameTarget.Detached
  }

  override fun close() = Unit
}
