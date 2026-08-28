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

  private var attachedTo: StyleBinding = node.binding

  /** Re-adds every held image when the node has been re-pointed at a new style. */
  internal fun ensureAttached() {
    ensureAttachedLocked()
  }

  private fun ensureAttachedLocked() {
    val binding = node.binding
    if (attachedTo === binding) return
    bitmapIds.entries.forEach { (key, id) ->
      node.logger?.i { "Re-adding bitmap $id" }
      binding.addImage(id, key.bitmap, key.isSdf, key.stretch)
    }
    painterIds.entries.forEach { (key, id) ->
      node.logger?.i { "Re-adding painter $id" }
      binding.addImage(id, key.renderToImage(), key.drawAsSdf, key.stretch)
    }
    // Published last: a throw above leaves the attachment incomplete, and the next sync retries
    // the whole replay.
    attachedTo = binding
  }

  internal fun acquireBitmap(key: BitmapKey): String {
    ensureAttachedLocked()
    try {
      bitmapCounter.increment(key) {
        val id = bitmapIds.addId(key)
        node.logger?.i { "Adding bitmap $id" }
        node.binding.addImage(id, key.bitmap, key.isSdf, key.stretch)
      }
    } catch (error: Throwable) {
      // A failed first upload must not leave a count that makes the retry skip the upload.
      bitmapCounter.decrement(key) { runCatching { bitmapIds.removeId(key) } }
      throw error
    }
    return bitmapIds.getId(key)
  }

  internal fun releaseBitmap(key: BitmapKey) {
    bitmapCounter.decrement(key) {
      val id = bitmapIds.removeId(key)
      node.logger?.i { "Removing bitmap $id" }
      node.binding.removeImage(id)
    }
  }

  /** The pixels MapLibre receives: an SDF painter's rasterization converted to a distance field. */
  private fun PainterKey.renderToImage(): ImageBitmap =
    rasterizePainter(painter, density, layoutDirection, size, alpha, colorFilter, drawAsSdf)

  internal fun acquirePainter(key: PainterKey): String {
    ensureAttachedLocked()
    try {
      painterCounter.increment(key) {
        val id = painterIds.addId(key)
        node.logger?.i { "Adding painter $id" }
        node.binding.addImage(id, key.renderToImage(), key.drawAsSdf, key.stretch)
      }
    } catch (error: Throwable) {
      // A failed first upload must not leave a count that makes the retry skip the upload.
      painterCounter.decrement(key) { runCatching { painterIds.removeId(key) } }
      throw error
    }
    return painterIds.getId(key)
  }

  internal fun releasePainter(key: PainterKey) {
    painterCounter.decrement(key) {
      val id = painterIds.removeId(key)
      node.logger?.i { "Removing painter $id" }
      node.binding.removeImage(id)
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
