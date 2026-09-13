package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import be.digitalia.compose.htmlconverter.htmlToAnnotatedString
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Embeds the captured style's credits so they travel with the saved or shared image. */
internal suspend fun ImageBitmap.withAttribution(
  attributions: List<String>,
  textMeasurer: TextMeasurer,
  density: Density,
  layoutDirection: LayoutDirection,
): ImageBitmap {
  if (attributions.isEmpty()) return this
  val text = attributions.joinToString(" | ") { htmlToAnnotatedString(it).text }
  val padding = 4f * density.density
  // Lay out narrow captures at a readable width, then scale the complete strip to fit.
  val textWidth = maxOf(width - padding * 2, 160f * density.density).roundToInt()
  val layout =
    textMeasurer.measure(
      text,
      style = TextStyle(color = Color.Black, fontSize = 10.sp),
      constraints = Constraints(maxWidth = textWidth),
      density = density,
      layoutDirection = layoutDirection,
    )
  val stripWidth = layout.size.width + padding * 2
  val stripHeight = layout.size.height + padding * 2
  val scale = minOf(1f, width / stripWidth, height / (stripHeight * 2))
  // Keep the composition-owned text measurer on the UI thread; raster work owns its output.
  return withContext(Dispatchers.Default) {
    ImageBitmap(width, height).also { output ->
      CanvasDrawScope().draw(
        density,
        layoutDirection,
        Canvas(output),
        Size(width.toFloat(), height.toFloat()),
      ) {
        drawImage(this@withAttribution)
        translate(top = height - stripHeight * scale) {
          scale(scale, pivot = Offset.Zero) {
            drawRect(Color.White.copy(alpha = 0.9f), size = Size(width / scale, stripHeight))
            drawText(layout, topLeft = Offset(padding, padding))
          }
        }
      }
    }
  }
}
