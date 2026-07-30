package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
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
    if (resizeOptions != null) {
      // Said rather than silently dropped: the image is uploaded whole, so it scales instead of
      // stretching, and a nine-patch background comes out distorted with nothing to explain why.
      // TODO(maplibre-native-ffi): Preserve stretchable image content insets once
      // the C API and Kotlin StyleImageOptions expose them.
      binding.logger?.w {
        "Image '$id' asked for content insets, which desktop cannot preserve: " +
          "maplibre-native-ffi's StyleImageOptions carries only pixelRatio and sdf. " +
          "The image will scale rather than stretch."
      }
    }
    val pixels = image.toPremultipliedRgba8()
    binding.withMap { map ->
      map.setStyleImage(
        imageId = id,
        image = pixels,
        options =
          StyleImageOptions().also {
            it.sdf = sdf
            it.pixelRatio = getScale()
          },
      )
    }
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
