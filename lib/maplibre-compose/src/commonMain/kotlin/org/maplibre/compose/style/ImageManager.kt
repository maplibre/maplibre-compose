package org.maplibre.compose.style

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.takeOrElse
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import org.maplibre.compose.util.ImageStretch
import org.maplibre.compose.util.toImageBitmap

internal class ImageManager(private val node: StyleNode) {
  private val bitmapIds = IncrementingIdMap<BitmapKey>("bitmap")
  private val bitmapCounter = ReferenceCounter<BitmapKey>()
  private val bitmapDefinitions = linkedMapOf<BitmapKey, StyleImageDefinition>()

  private val painterIds = IncrementingIdMap<PainterKey>("painter")
  private val painterCounter = ReferenceCounter<PainterKey>()
  private val painterDefinitions = linkedMapOf<PainterKey, StyleImageDefinition>()

  internal val desiredImages: List<StyleImageDefinition>
    get() = bitmapDefinitions.values.toList() + painterDefinitions.values

  internal fun acquireBitmap(key: BitmapKey): String {
    bitmapCounter.increment(key) {
      val id = bitmapIds.addId(key)
      bitmapDefinitions[key] =
        StyleImageDefinition(id, ImageSnapshot.capture(key.bitmap), key.isSdf, key.stretch)
      node.scheduleApplyChanges()
    }
    return bitmapIds.getId(key)
  }

  internal fun releaseBitmap(key: BitmapKey) {
    bitmapCounter.decrement(key) {
      bitmapIds.removeId(key)
      bitmapDefinitions.remove(key)
      node.scheduleApplyChanges()
    }
  }

  private fun PainterKey.drawToBitmap(): ImageBitmap {
    val size =
      with(density) {
        size?.let { Size(it.width.toPx(), it.height.toPx()) }
          ?: painter.intrinsicSize.takeOrElse { Size(16.dp.toPx(), 16.dp.toPx()) }
      }
    val bitmap = ImageBitmap(size.width.toInt(), size.height.toInt())
    CanvasDrawScope().draw(density, layoutDirection, Canvas(bitmap), size) {
      with(painter) { draw(size, alpha, colorFilter) }
    }
    return bitmap
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

  internal fun acquirePainter(key: PainterKey): String {
    painterCounter.increment(key) {
      val id = painterIds.addId(key)
      key.drawToBitmap().let { bitmap ->
        val resolved = if (key.drawAsSdf) bitmap.toSdf() else bitmap
        painterDefinitions[key] =
          StyleImageDefinition(id, ImageSnapshot.capture(resolved), key.drawAsSdf, key.stretch)
      }
      node.scheduleApplyChanges()
    }
    return painterIds.getId(key)
  }

  internal fun releasePainter(key: PainterKey) {
    painterCounter.decrement(key) {
      painterIds.removeId(key)
      painterDefinitions.remove(key)
      node.scheduleApplyChanges()
    }
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
}
