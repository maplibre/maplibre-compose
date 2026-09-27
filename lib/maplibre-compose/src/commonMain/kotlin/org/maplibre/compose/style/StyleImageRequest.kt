package org.maplibre.compose.style

import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.maplibre.compose.expressions.ast.BitmapLiteral
import org.maplibre.compose.expressions.ast.PainterLiteral
import org.maplibre.compose.map.ResolvedStyleImage

internal sealed interface StyleImageRequest {
  data class Bitmap(val literal: BitmapLiteral) : StyleImageRequest

  data class Painter(
    val literal: PainterLiteral,
    val graphicsContext: GraphicsContext,
    val density: Density,
    val layoutDirection: LayoutDirection,
  ) : StyleImageRequest
}

internal fun StyleImageRequest.Bitmap.prepare() =
  ResolvedStyleImage(ImageSnapshot.capture(literal.value), literal.sdf, literal.stretch)

internal suspend fun StyleImageRequest.Painter.prepare(): ResolvedStyleImage {
  val bitmap =
    renderPainter(
      literal.value,
      graphicsContext,
      density,
      layoutDirection,
      literal.size,
      literal.sdf,
      literal.alpha,
      literal.colorFilter,
    )
  return ResolvedStyleImage(ImageSnapshot.capture(bitmap), literal.sdf, literal.stretch)
}
