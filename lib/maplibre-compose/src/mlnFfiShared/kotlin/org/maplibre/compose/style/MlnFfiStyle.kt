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
import org.maplibre.compose.sources.toStyleSpecType
import org.maplibre.compose.util.ImageResizeOptions
import org.maplibre.compose.util.toJsonElement
import org.maplibre.compose.util.toPremultipliedRgba8
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.style.ImageContent
import org.maplibre.nativeffi.style.ImageStretch
import org.maplibre.nativeffi.style.StyleImageOptions

/**
 * The style of a live map. Everything here runs on the map's owner thread through [binding].
 *
 * Reads of the base style reconstruct [UnknownSource] and [UnknownLayer] from what MapLibre
 * reports, because a style loaded from a URL or JSON has no Kotlin objects behind it. Those
 * reconstructions are views: the map remains the source of truth.
 */
internal class MlnFfiStyle(
  private val binding: StyleBinding,
  /**
   * The display scale the images handed to [addImage] were rasterized at. MapLibre sizes a style
   * image as `pixels / pixelRatio`, so a wrong scale here draws every icon at the wrong size.
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
    // The stretchable region and the content box are the same four numbers, as on Android.
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
   * Insets are distances in from each edge, converted at the same [scale] the bitmap was rasterized
   * at. A degenerate box (sides meeting or crossing) is warned about and dropped rather than thrown
   * for: it is reachable from Dp insets on a small bitmap at 2x, and MapLibre divides by a stretch
   * sum that would be zero.
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
   * Reads back the stretchable intervals MapLibre stored for an image. Exists for tests;
   * [org.maplibre.nativeffi.style.StyleImageInfo] reports only their count.
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
    source.detach(binding)
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
    val anchor =
      binding.withMap { map ->
        val ids = map.styleLayerIds()
        val index = ids.indexOf(id)
        require(index >= 0) { "Layer ID '$id' not found in base style" }
        ids.getOrNull(index + 1).orEmpty()
      } ?: return
    layer.attach(binding, beforeLayerId = anchor)
  }

  override fun addLayerBelow(id: String, layer: Layer) {
    layer.attach(binding, beforeLayerId = id)
  }

  override fun addLayerAt(index: Int, layer: Layer) {
    val anchor =
      binding.withMap { map ->
        val ids = map.styleLayerIds()
        require(index in 0..ids.size) {
          "Layer index $index is outside the valid range 0..${ids.size}"
        }
        ids.getOrNull(index).orEmpty()
      } ?: return
    layer.attach(binding, beforeLayerId = anchor)
  }

  override fun removeLayer(layer: Layer) {
    layer.detach(binding)
  }

  /**
   * Rebuilds a source that the base style owns. Only [UnknownSource] is produced: the typed source
   * classes carry construction options MapLibre does not report back.
   */
  private fun reconstructSource(map: MapHandle, id: String): Source =
    UnknownSource(id, sourceDefinition(map, id)).also { it.bindExisting(binding) }

  private fun sourceDefinition(map: MapHandle, id: String): JsonObject = buildJsonObject {
    // Through toStyleSpecType rather than toString: the enum's toString is not style-spec JSON.
    map.styleSourceType(id)?.toStyleSpecType()?.let { put("type", it) }
    map.styleSourceInfo(id)?.attribution?.let { put("attribution", it) }
  }

  private fun reconstructLayer(map: MapHandle, id: String): Layer {
    val definition =
      (map.styleLayerJson(id)?.toJsonElement() as? JsonObject)
        ?: buildJsonObject { map.styleLayerType(id)?.let { put("type", it) } }
    return UnknownLayer(id, definition).also { it.bindExisting(binding) }
  }
}
