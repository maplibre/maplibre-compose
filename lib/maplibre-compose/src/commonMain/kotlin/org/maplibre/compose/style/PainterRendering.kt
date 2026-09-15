package org.maplibre.compose.style

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.takeOrElse
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import org.maplibre.compose.util.toImageBitmap

internal fun validatePainterSize(painter: Painter, size: DpSize?) {
  val dimensions =
    size?.let { Size(it.width.value, it.height.value) }
      ?: painter.intrinsicSize.takeIf { it.isSpecified }
  require(dimensions == null || (dimensions.width > 0f && dimensions.height > 0f)) {
    "Painter image size must have positive width and height, but was $dimensions. " +
      "Pass a size with positive width and height."
  }
}

internal suspend fun renderPainter(
  painter: Painter,
  graphicsContext: GraphicsContext,
  density: Density,
  layoutDirection: LayoutDirection,
  size: DpSize?,
  drawAsSdf: Boolean,
  alpha: Float,
  colorFilter: ColorFilter?,
): ImageBitmap {
  validatePainterSize(painter, size)
  val pixels =
    with(density) {
      size?.let { Size(it.width.toPx(), it.height.toPx()) }
        ?: painter.intrinsicSize.takeOrElse { Size(16.dp.toPx(), 16.dp.toPx()) }
    }
  val layer = graphicsContext.createGraphicsLayer()
  try {
    layer.record(
      density,
      layoutDirection,
      IntSize(ceil(pixels.width).toInt(), ceil(pixels.height).toInt()),
    ) {
      with(painter) { draw(pixels, alpha, colorFilter) }
    }
    return layer.captureImage(density, layoutDirection).let { if (drawAsSdf) it.toSdf() else it }
  } finally {
    graphicsContext.releaseGraphicsLayer(layer)
  }
}

private fun ImageBitmap.toSdf(radius: Double = 8.0, cutoff: Double = 0.25): ImageBitmap {
  val buffer = ceil(radius * (1.0 - cutoff)).toInt()
  val w = width + 2 * buffer
  val h = height + 2 * buffer
  val pixels = IntArray(w * h)
  readPixels(pixels, bufferOffset = w * buffer + buffer, stride = w)
  convertToSdf(pixels, w, radius, cutoff)
  return pixels.toImageBitmap(w, pixels.size / w)
}
