package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.maplibre.compose.expressions.ast.BitmapLiteral
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.PainterLiteral
import org.maplibre.compose.style.StyleImageRequest

internal data class LayerPropertyImages(
  val bitmaps: Map<BitmapLiteral, StyleImageRequest>,
  val painters: Map<PainterLiteral, StyleImageRequest>,
) {
  val requests: Set<StyleImageRequest> = (bitmaps.values + painters.values).toSet()
}

@Composable
internal fun rememberLayerPropertyImages(
  expressions: List<Expression<*>>,
  density: Density,
  layoutDirection: LayoutDirection,
): LayerPropertyImages {
  val literals =
    remember(expressions) {
      val bitmaps = mutableSetOf<BitmapLiteral>()
      val painters = mutableSetOf<PainterLiteral>()
      expressions.forEach { expression ->
        expression.visit {
          when (it) {
            is BitmapLiteral -> bitmaps += it
            is PainterLiteral -> painters += it
            else -> Unit
          }
        }
      }
      bitmaps to painters
    }
  val graphicsContext = if (literals.second.isEmpty()) null else LocalGraphicsContext.current
  return remember(literals, density, layoutDirection, graphicsContext) {
    LayerPropertyImages(
      literals.first.associateWith { StyleImageRequest.Bitmap(it) },
      literals.second.associateWith {
        StyleImageRequest.Painter(it, requireNotNull(graphicsContext), density, layoutDirection)
      },
    )
  }
}
