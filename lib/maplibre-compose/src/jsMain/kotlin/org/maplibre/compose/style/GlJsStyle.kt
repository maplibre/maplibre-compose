package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import js.objects.unsafeJso
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.SourceHandle
import org.maplibre.compose.gljs.StyleImageMetadata
import org.maplibre.compose.gljs.keys
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.UnknownLayer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.UnknownSource
import org.maplibre.compose.util.ImageResizeOptions
import org.maplibre.compose.util.toGlJsImage
import org.maplibre.compose.util.toJsonElement

/** Reads of the base style reconstruct [UnknownSource] and [UnknownLayer] as views over the map. */
internal class GlJsStyle(
  private val binding: GlJsStyleBinding,
  /**
   * The display scale the images handed to [addImage] were rasterized at. MapLibre sizes a style
   * image as `pixels / pixelRatio`.
   */
  private val getScale: () -> Float,
) : Style {

  override fun addImage(
    id: String,
    image: ImageBitmap,
    sdf: Boolean,
    resizeOptions: ImageResizeOptions?,
  ) {
    val scale = getScale()
    val pixels = image.toGlJsImage()
    // The stretchable region and the content box are the same four numbers, as on every platform.
    val box = resizeOptions?.let { contentBox(id, image, it, scale) }
    val metadata =
      unsafeJso<StyleImageMetadata> {
        pixelRatio = scale.toDouble()
        this.sdf = sdf
        box?.let {
          stretchX = arrayOf(arrayOf(it.left, it.right))
          stretchY = arrayOf(arrayOf(it.top, it.bottom))
          content = arrayOf(it.left, it.top, it.right, it.bottom)
        }
      }
    binding.withMap { map ->
      // Replacing an image is an update, not a second add; MapLibre warns and ignores a duplicate.
      if (map.hasImage(id)) map.removeImage(id)
      map.addImage(id, pixels, metadata)
    }
  }

  /**
   * Null when [resizeOptions] does not fit [image]; MapLibre would divide by a stretch sum of zero.
   */
  private fun contentBox(
    id: String,
    image: ImageBitmap,
    resizeOptions: ImageResizeOptions,
    scale: Float,
  ): ContentBox? {
    val box =
      with(Density(scale)) {
        ContentBox(
          left = resizeOptions.left.toPx().toDouble(),
          top = resizeOptions.top.toPx().toDouble(),
          right = image.width - resizeOptions.right.toPx().toDouble(),
          bottom = image.height - resizeOptions.bottom.toPx().toDouble(),
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

  private data class ContentBox(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
  )

  override fun removeImage(id: String) {
    binding.withMap { map -> if (map.hasImage(id)) map.removeImage(id) }
  }

  override fun getSource(id: String): Source? = binding.withMap { map ->
    if (map.getSource<SourceHandle>(id) == null) null else reconstructSource(id)
  }

  override fun getSources(): List<Source> =
    binding.withMap { map -> sourceIds(map).map { reconstructSource(it) } }.orEmpty()

  override fun addSource(source: Source) {
    source.attach(binding)
  }

  override fun removeSource(source: Source) {
    source.detach(binding)
  }

  override fun getLayer(id: String): Layer? = binding.withMap { map ->
    map.getLayer(id)?.let { reconstructLayer(map, id) }
  }

  override fun getLayers(): List<Layer> =
    binding.withMap { map -> map.getLayersOrder().map { reconstructLayer(map, it) } }.orEmpty()

  override fun addLayer(layer: Layer) {
    layer.attach(binding, beforeLayerId = "")
  }

  override fun addLayerAbove(id: String, layer: Layer) {
    // "Above id" is "below whatever currently sits above id", which is the next layer along.
    val anchor =
      binding.withMap { map ->
        val ids = map.getLayersOrder()
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
        val ids = map.getLayersOrder()
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
   * The definition comes from the source object, not `getStyle()`, which reports the stylesheet as
   * written and so omits anything resolved from a TileJSON URL.
   */
  private fun reconstructSource(id: String): Source =
    UnknownSource(id, sourceDefinition(id)).also { it.bindExisting(binding) }

  private fun sourceDefinition(id: String): JsonObject = buildJsonObject {
    val source =
      binding.withMap { map -> map.getSource<SourceHandle>(id) } ?: return@buildJsonObject
    put("type", source.type)
    source.attribution?.let { put("attribution", it) }
  }

  private fun sourceIds(map: MaplibreMap): List<String> = map.getStyle().sources.keys().toList()

  /**
   * From the stylesheet, not the live layer: `StyleLayer` reports evaluated properties where
   * re-adding one needs the source expressions.
   */
  private fun reconstructLayer(map: MaplibreMap, id: String): Layer {
    val definition =
      map.getStyle().layers.firstOrNull { it.id == id }?.toJsonElement() as? JsonObject
        ?: buildJsonObject {
          put("id", id)
          map.getLayer(id)?.let { put("type", it.type) }
        }
    return UnknownLayer(id, definition).also { it.bindExisting(binding) }
  }
}
