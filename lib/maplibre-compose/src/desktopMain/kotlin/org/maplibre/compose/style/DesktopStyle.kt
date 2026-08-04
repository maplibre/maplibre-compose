package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.UnknownLayer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.UnknownSource
import org.maplibre.compose.util.ImageResizeOptions
import org.maplibre.compose.util.toJsonElement
import org.maplibre.compose.util.toPremultipliedRgba8
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.style.ImageContent
import org.maplibre.nativeffi.style.ImageStretch
import org.maplibre.nativeffi.style.StyleImageOptions

/**
 * The style of a live desktop map.
 *
 * Sources and layers are live descriptors; this is what binds them to a map. Everything here runs
 * on the map's owner thread through [binding].
 *
 * Reads of the base style reconstruct [UnknownSource] and [UnknownLayer] from what MapLibre
 * reports, because a style loaded from a URL or JSON has no Kotlin objects behind it. Those
 * reconstructions are views, not owners: removing one removes it from the map, but the map is the
 * source of truth.
 */
internal class DesktopStyle(
  private val binding: StyleBinding,
  /**
   * The display scale the images handed to [addImage] were rasterized at.
   *
   * `ImageManager` draws a painter through Compose's density, so a 16.dp icon is a 32x32 bitmap on
   * a 2x display. MapLibre sizes a style image as `pixels / pixelRatio`, so telling it 1 there
   * draws that icon at 32 logical pixels — every marker twice the size it should be, on exactly the
   * displays where nothing else looks wrong. iOS passes the same scale into `toUIImage`, and
   * Android gets it from the `Bitmap`'s own density.
   */
  private val getScale: () -> Float = { 1f },
) : Style {

  override fun addImage(
    id: String,
    image: ImageBitmap,
    sdf: Boolean,
    resizeOptions: ImageResizeOptions?,
  ) {
    val scale = getScale()
    val pixels = image.toPremultipliedRgba8()
    // The stretchable region and the content box are the same four numbers, as on Android: the
    // whole middle of the image stretches, and label padding is measured against that same middle.
    val box = resizeOptions?.let { contentBox(id, image, it, scale) }
    binding.withMap { map ->
      map.setStyleImage(
        imageId = id,
        image = pixels,
        options =
          StyleImageOptions().also {
            it.sdf = sdf
            it.pixelRatio = scale
            it.stretchX = box?.let { content -> listOf(ImageStretch(content.left, content.right)) }
            it.stretchY = box?.let { content -> listOf(ImageStretch(content.top, content.bottom)) }
            it.content = box
          },
      )
    }
  }

  /**
   * Turns [resizeOptions] into a content box in image pixels, or null when it does not fit [image].
   *
   * The insets are distances in from each edge, so the far edges are measured back from the image's
   * size, and every conversion goes through the same [scale] the bitmap was rasterized at — a 2x
   * bitmap is twice as many pixels across, and converting its insets at 1x would place them at half
   * the distance in.
   *
   * A box whose sides meet or cross is dropped with a warning rather than thrown for, because it is
   * reachable without anyone writing anything wrong: insets are [androidx.compose.ui.unit.Dp] and a
   * caller-supplied bitmap is a fixed number of pixels, so 6.dp insets on a 16x16 bitmap fit at 1x
   * and cross at 2x. Throwing would make that a display-dependent crash out of a Compose applier,
   * for a mis-fit that costs a stretch. Passing it through is not an option either: MapLibre's
   * `computeStretchSum` totals zero for an axis of zero-width intervals, and `getIconQuads` then
   * divides the box offsets by it, which is why the native binding rejects such an interval
   * outright — and an exception raised inside a style mutation is not one we can attribute.
   */
  private fun contentBox(
    id: String,
    image: ImageBitmap,
    resizeOptions: ImageResizeOptions,
    scale: Float,
  ): ImageContent? {
    val box =
      with(Density(scale)) {
        ImageContent(
          left = resizeOptions.left.toPx(),
          top = resizeOptions.top.toPx(),
          right = image.width - resizeOptions.right.toPx(),
          bottom = image.height - resizeOptions.bottom.toPx(),
        )
      }
    if (box.left < box.right && box.top < box.bottom) return box
    binding.logger?.w {
      "Image '$id' asked for content insets that leave nothing to stretch: at scale $scale they " +
        "put the box at $box in a ${image.width}x${image.height} image. The image will be " +
        "uploaded whole, so it scales rather than stretches."
    }
    return null
  }

  /**
   * Reads back the stretchable intervals MapLibre stored for an image.
   *
   * Nothing in the public API needs this; it exists so a test can assert on the numbers themselves,
   * which [org.maplibre.nativeffi.style.StyleImageInfo] reports only the count of.
   */
  internal fun imageStretches(id: String): Pair<List<ImageStretch>, List<ImageStretch>>? =
    binding.withMap { map ->
      map.styleImageStretches(id)
    }

  override fun removeImage(id: String) {
    binding.withMap { map -> map.removeStyleImage(id) }
  }

  override fun getSource(id: String): Source? = binding.withMap { map ->
    if (!map.styleSourceExists(id)) null else reconstructSource(map, id)
  }

  override fun getSources(): List<Source> =
    binding.withMap { map -> map.styleSourceIds().map { reconstructSource(map, it) } }.orEmpty()

  override fun addSource(source: Source) {
    source.attach(binding)
  }

  override fun removeSource(source: Source) {
    source.detach()
  }

  override fun getLayer(id: String): Layer? = binding.withMap { map ->
    if (!map.styleLayerExists(id)) null else reconstructLayer(map, id)
  }

  override fun getLayers(): List<Layer> =
    binding.withMap { map -> map.styleLayerIds().map { reconstructLayer(map, it) } }.orEmpty()

  /** Adds [layer] on top of every existing layer. */
  override fun addLayer(layer: Layer) {
    // MapLibre has no explicit "on top"; an empty anchor means the same thing.
    layer.attach(binding, beforeLayerId = "")
  }

  override fun addLayerAbove(id: String, layer: Layer) {
    // "Above id" is "below whatever currently sits above id", which is the next layer along.
    val anchor = binding.withMap { map -> map.styleLayerIds().nextAfter(id) } ?: ""
    layer.attach(binding, beforeLayerId = anchor)
  }

  override fun addLayerBelow(id: String, layer: Layer) {
    layer.attach(binding, beforeLayerId = id)
  }

  override fun addLayerAt(index: Int, layer: Layer) {
    val anchor = binding.withMap { map -> map.styleLayerIds().getOrNull(index) } ?: ""
    layer.attach(binding, beforeLayerId = anchor)
  }

  override fun removeLayer(layer: Layer) {
    layer.detach()
  }

  /**
   * Rebuilds a source that the base style owns.
   *
   * Only [UnknownSource] is produced. The typed source classes carry construction options MapLibre
   * does not report back — a vector source cannot say whether it was built from a URL or a tile
   * list — so presenting one would imply a fidelity that is not there. [UnknownSource] replays
   * exactly what MapLibre reported.
   */
  private fun reconstructSource(map: MapHandle, id: String): Source =
    UnknownSource(id, sourceDefinition(map, id)).also { it.bindExisting(binding) }

  private fun sourceDefinition(map: MapHandle, id: String): JsonObject = buildJsonObject {
    map.styleSourceType(id)?.let { put("type", it.toString()) }
    map.styleSourceInfo(id)?.attribution?.let { put("attribution", it) }
  }

  private fun reconstructLayer(map: MapHandle, id: String): Layer {
    val definition =
      (map.styleLayerJson(id)?.toJsonElement() as? JsonObject)
        ?: buildJsonObject { map.styleLayerType(id)?.let { put("type", it) } }
    return UnknownLayer(id, definition).also { it.bindExisting(binding) }
  }
}

/** The id directly after [id], or null when [id] is last or absent. */
private fun List<String>.nextAfter(id: String): String? {
  val index = indexOf(id)
  return if (index < 0 || index + 1 >= size) null else this[index + 1]
}
