package org.maplibre.compose.style

import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.maplibre.compose.expressions.ast.BitmapLiteral
import org.maplibre.compose.expressions.ast.PainterLiteral
import org.maplibre.compose.util.ImageStretch

internal sealed interface StyleImageRequest {
  data class Bitmap(val literal: BitmapLiteral) : StyleImageRequest

  data class Painter(
    val literal: PainterLiteral,
    val graphicsContext: GraphicsContext,
    val density: Density,
    val layoutDirection: LayoutDirection,
  ) : StyleImageRequest
}

internal data class StyleImageContent(
  val image: ImageSnapshot,
  val sdf: Boolean,
  val stretch: ImageStretch?,
)

internal fun StyleImageRequest.Bitmap.prepare() =
  StyleImageContent(ImageSnapshot.capture(literal.value), literal.sdf, literal.stretch)

internal suspend fun StyleImageRequest.Painter.prepare(): StyleImageContent {
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
  return StyleImageContent(ImageSnapshot.capture(bitmap), literal.sdf, literal.stretch)
}
