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

  private val painterIds = IncrementingIdMap<PainterKey>("painter")
  private val painterCounter = ReferenceCounter<PainterKey>()

  private var flushedTo: StyleBinding? = null
  private val uploaded = mutableSetOf<String>()

  /** The image ids the composition currently holds. */
  internal val heldIds: List<String>
    get() = bitmapIds.entries.map { it.value } + painterIds.entries.map { it.value }

  /**
   * Uploads held images onto [binding] and removes images this manager no longer holds. The style
   * applier is the only caller.
   */
  internal fun flushTo(binding: StyleBinding) {
    if (flushedTo !== binding) {
      uploaded.clear()
      flushedTo = binding
    }
    val desired = linkedMapOf<String, () -> Unit>()
    bitmapIds.entries.forEach { (key, id) ->
      desired[id] = {
        node.logger?.i { "Adding bitmap $id" }
        binding.addImage(id, key.bitmap, key.isSdf, key.stretch)
      }
    }
    painterIds.entries.forEach { (key, id) ->
      desired[id] = {
        node.logger?.i { "Adding painter $id" }
        binding.addImage(id, key.renderToImage(), key.drawAsSdf, key.stretch)
      }
    }
    uploaded
      .filter { it !in desired }
      .forEach { id ->
        node.logger?.i { "Removing image $id" }
        binding.removeImage(id)
        uploaded.remove(id)
      }
    desired.forEach { (id, upload) ->
      if (id !in uploaded) {
        upload()
        uploaded += id
      }
    }
  }

  internal fun acquireBitmap(key: BitmapKey): String {
    bitmapCounter.increment(key) { bitmapIds.addId(key) }
    node.requestSync()
    return bitmapIds.getId(key)
  }

  internal fun releaseBitmap(key: BitmapKey) {
    bitmapCounter.decrement(key) { bitmapIds.removeId(key) }
    node.requestSync()
  }

  /** The pixels MapLibre receives: an SDF painter's rasterization converted to a distance field. */
  private fun PainterKey.renderToImage(): ImageBitmap =
    rasterizePainter(painter, density, layoutDirection, size, alpha, colorFilter, drawAsSdf)

  internal fun acquirePainter(key: PainterKey): String {
    painterCounter.increment(key) { painterIds.addId(key) }
    node.requestSync()
    return painterIds.getId(key)
  }

  internal fun releasePainter(key: PainterKey) {
    painterCounter.decrement(key) { painterIds.removeId(key) }
    node.requestSync()
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

/**
 * Rasterizes [painter] at [density] and [layoutDirection], at [size] or the painter's intrinsic
 * size, converting the result to a signed distance field when [asSdf] is set.
 */
internal fun rasterizePainter(
  painter: Painter,
  density: Density,
  layoutDirection: LayoutDirection,
  size: DpSize?,
  alpha: Float = 1f,
  colorFilter: ColorFilter? = null,
  asSdf: Boolean = false,
): ImageBitmap {
  val pxSize =
    with(density) {
      size?.let { Size(it.width.toPx(), it.height.toPx()) }
        ?: painter.intrinsicSize.takeOrElse { Size(16.dp.toPx(), 16.dp.toPx()) }
    }
  val bitmap = ImageBitmap(pxSize.width.toInt(), pxSize.height.toInt())
  CanvasDrawScope().draw(density, layoutDirection, Canvas(bitmap), pxSize) {
    with(painter) { draw(pxSize, alpha, colorFilter) }
  }
  return if (asSdf) bitmap.toSdf() else bitmap
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
