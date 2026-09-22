package org.maplibre.compose.layers

import androidx.compose.ui.graphics.GraphicsContext
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

internal fun layerPropertyImages(
  expressions: List<Expression<*>>,
  density: Density,
  layoutDirection: LayoutDirection,
  graphicsContext: () -> GraphicsContext,
): LayerPropertyImages {
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
  return LayerPropertyImages(
    bitmaps.associateWith { StyleImageRequest.Bitmap(it) },
    painters.associateWith {
      StyleImageRequest.Painter(it, graphicsContext(), density, layoutDirection)
    },
  )
}
