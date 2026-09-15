package org.maplibre.compose.style

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.compose.util.ImageStretch

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

  private var pendingProperties = 0
  private val painterMutex = Mutex()
  private val painterCounter = ReferenceCounter<PainterKey>()
  private val painterContent = mutableMapOf<PainterKey, ContentKey>()

  internal val desiredImages: List<StyleImageDefinition>
    get() = definitions.values.toList()

  internal val hasPendingImages: Boolean
    get() = pendingProperties != 0

  internal fun beginImagePreparation() {
    pendingProperties++
    node.scheduleApplyChanges()
  }

  internal fun endImagePreparation() {
    pendingProperties--
    node.scheduleApplyChanges()
  }

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
              renderPainter(
                key.painter,
                graphicsContext,
                key.density,
                key.layoutDirection,
                key.size,
                key.drawAsSdf,
                key.alpha,
                key.colorFilter,
              )
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
