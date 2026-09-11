package org.maplibre.compose.style

import androidx.compose.ui.geometry.Size
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.compose.util.ImageStretch
import org.maplibre.compose.util.toImageBitmap

/**
 * Registers bitmaps and painters as style images and shares one style image between every reference
 * that resolves to the same pixels.
 *
 * Bitmap and painter keys count references from compiled expressions. Each key resolves once to a
 * [ContentKey], which counts the keys that resolved to it and owns the style image. Painters
 * without value equality, such as vector painters from different call sites, therefore still share
 * an image when they draw the same pixels.
 */
internal class ImageManager(private val node: StyleNode) {
  private val imageIds = IncrementingId("image")
  private val contentCounter = ReferenceCounter<ContentKey>()
  private val definitions = linkedMapOf<ContentKey, StyleImageDefinition>()

  private val bitmapCounter = ReferenceCounter<BitmapKey>()
  private val bitmapContent = mutableMapOf<BitmapKey, ContentKey>()

  private val painterMutex = Mutex()
  private val painterCounter = ReferenceCounter<PainterKey>()
  private val painterContent = mutableMapOf<PainterKey, ContentKey>()

  internal val desiredImages: List<StyleImageDefinition>
    get() = definitions.values.toList()

  internal fun acquireBitmap(key: BitmapKey): String {
    bitmapCounter.increment(key) {
      bitmapContent[key] =
        acquireContent(ContentKey(ImageSnapshot.capture(key.bitmap), key.isSdf, key.stretch))
    }
    return definitions.getValue(bitmapContent.getValue(key)).id
  }

  internal fun releaseBitmap(key: BitmapKey) {
    bitmapCounter.decrement(key) { releaseContent(bitmapContent.remove(key)!!) }
  }

  internal suspend fun acquirePainter(key: PainterKey, graphicsContext: GraphicsContext): String =
    painterMutex.withLock {
      val content =
        painterContent[key]
          ?: run {
            val bitmap =
              key.drawToBitmap(graphicsContext).let { if (key.drawAsSdf) it.toSdf() else it }
            ContentKey(ImageSnapshot.capture(bitmap), key.drawAsSdf, key.stretch)
          }
      painterCounter.increment(key) { painterContent[key] = acquireContent(content) }
      definitions.getValue(painterContent.getValue(key)).id
    }

  internal fun releasePainter(key: PainterKey) {
    painterCounter.decrement(key) { releaseContent(painterContent.remove(key)!!) }
  }

  private fun acquireContent(key: ContentKey): ContentKey {
    contentCounter.increment(key) {
      definitions[key] = StyleImageDefinition(imageIds.next(), key.image, key.sdf, key.stretch)
      node.scheduleApplyChanges()
    }
    return key
  }

  private fun releaseContent(key: ContentKey) {
    contentCounter.decrement(key) {
      definitions.remove(key)
      node.scheduleApplyChanges()
    }
  }

  private suspend fun PainterKey.drawToBitmap(graphicsContext: GraphicsContext): ImageBitmap {
    val size =
      with(density) {
        size?.let { Size(it.width.toPx(), it.height.toPx()) }
          ?: painter.intrinsicSize.takeOrElse { Size(16.dp.toPx(), 16.dp.toPx()) }
      }
    val layer = graphicsContext.createGraphicsLayer()
    try {
      layer.record(density, layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) {
        with(painter) { draw(size, alpha, colorFilter) }
      }
      return layer.toImageBitmap()
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

  /** The resolved pixels and style image options that identify one style image. */
  private data class ContentKey(
    val image: ImageSnapshot,
    val sdf: Boolean,
    val stretch: ImageStretch?,
  )

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
