package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import org.maplibre.compose.expressions.ast.BitmapLiteral
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.PainterLiteral
import org.maplibre.compose.style.ImageSnapshot
import org.maplibre.compose.style.MapNodeApplier
import org.maplibre.compose.style.StyleImageCache
import org.maplibre.compose.style.StyleImageDefinition
import org.maplibre.compose.style.StyleImageNode
import org.maplibre.compose.style.StyleNode
import org.maplibre.compose.style.renderPainter
import org.maplibre.compose.util.ImageStretch

internal class LayerPropertyImages(
  private val bitmaps: Map<BitmapKey, String>,
  private val painters: Map<PainterKey, String>,
  private val density: Density,
  private val layoutDirection: LayoutDirection,
) {
  fun resolve(bitmap: BitmapLiteral): String = bitmaps.getValue(bitmap.imageKey())

  fun resolve(painter: PainterLiteral): String =
    painters.getValue(painter.imageKey(density, layoutDirection))
}

@Composable
internal fun rememberLayerPropertyImages(
  expression: Expression<*>,
  node: StyleNode,
  density: Density,
  layoutDirection: LayoutDirection,
): LayerPropertyImages? {
  val (bitmapKeys, painterKeys) =
    remember(expression, density, layoutDirection) {
      val bitmaps = mutableSetOf<BitmapKey>()
      val painters = mutableSetOf<PainterKey>()
      expression.visit {
        when (it) {
          is BitmapLiteral -> bitmaps.add(it.imageKey())
          is PainterLiteral -> painters.add(it.imageKey(density, layoutDirection))
          else -> Unit
        }
      }
      bitmaps to painters
    }
  val bitmaps = bitmapKeys.associateWith { bitmap ->
    key(bitmap) {
      val request =
        remember(node, bitmap) {
          node.images.bitmap(bitmap) {
            StyleImageCache.Content(
              ImageSnapshot.capture(bitmap.bitmap),
              bitmap.isSdf,
              bitmap.stretch,
            )
          }
        }
      val definition = requireNotNull(request.definition)
      ImageDeclaration(request, definition)
      definition.id
    }
  }
  var pending = false
  val painters = painterKeys.associateWith { painter ->
    key(painter) {
      val graphicsContext = LocalGraphicsContext.current
      val request =
        remember(node, painter, graphicsContext) {
          node.images.painter(painter to graphicsContext) {
            val bitmap =
              renderPainter(
                painter.painter,
                graphicsContext,
                painter.density,
                painter.layoutDirection,
                painter.size,
                painter.drawAsSdf,
                painter.alpha,
                painter.colorFilter,
              )
            StyleImageCache.Content(
              ImageSnapshot.capture(bitmap),
              painter.drawAsSdf,
              painter.stretch,
            )
          }
        }
      val definition by
        key(request) {
          produceState(request.definition) { value = request.resolve() }
        }
      ImageDeclaration(request, definition)
      if (definition == null) pending = true
      definition?.id
    }
  }
  if (pending) return null
  return remember(bitmaps, painters, density, layoutDirection) {
    LayerPropertyImages(
      bitmaps,
      painters.mapValues { requireNotNull(it.value) },
      density,
      layoutDirection,
    )
  }
}

/**
 * Image ownership changes only when Compose applies this node, never while compiling a property.
 */
@Composable
private fun ImageDeclaration(request: StyleImageCache.Request, definition: StyleImageDefinition?) {
  ComposeNode<StyleImageNode, MapNodeApplier>(
    factory = ::StyleImageNode,
    update = {
      set(request) { this.request = it }
      set(definition) { this.definition = it }
    },
  )
}

internal data class BitmapKey(
  val bitmap: ImageBitmap,
  val isSdf: Boolean,
  val stretch: ImageStretch?,
)

internal data class PainterKey(
  val painter: Painter,
  val density: Density,
  val layoutDirection: LayoutDirection,
  val size: DpSize?,
  val drawAsSdf: Boolean,
  val stretch: ImageStretch?,
  val alpha: Float,
  val colorFilter: ColorFilter?,
)

private fun BitmapLiteral.imageKey() = BitmapKey(value, sdf, stretch)

private fun PainterLiteral.imageKey(density: Density, layoutDirection: LayoutDirection) =
  PainterKey(value, density, layoutDirection, size, sdf, stretch, alpha, colorFilter)
