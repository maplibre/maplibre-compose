package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.UnknownLayer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.UnknownSource
import org.maplibre.compose.sources.toStyleSpecEncoding
import org.maplibre.compose.sources.toStyleSpecType
import org.maplibre.compose.util.ImageStretch
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toJsonElement
import org.maplibre.compose.util.toPremultipliedRgba8
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.style.ImageContent
import org.maplibre.nativeffi.style.ImageStretch as FfiImageStretch
import org.maplibre.nativeffi.style.SourceType
import org.maplibre.nativeffi.style.StyleImageOptions
import org.maplibre.nativeffi.style.TileJson
import org.maplibre.nativeffi.style.TileScheme

/** The style of a live map. Everything here runs on the map's owner thread through [binding]. */
internal class MlnFfiStyle(
  private val binding: MlnFfiStyleBinding,
  /** The display scale the images handed to [addImage] were rasterized at. */
  private val getScale: () -> Float = { 1f },
) : Style {

  override fun addImage(id: String, image: ImageBitmap, sdf: Boolean, stretch: ImageStretch?) {
    val scale = getScale()
    val pixels = image.toPremultipliedRgba8()
    val stretchPx = stretch?.resolve(image.width, image.height, scale)
    binding.mutateMap { map ->
      map.setStyleImage(
        imageId = id,
        image = pixels,
        options =
          StyleImageOptions().also { options ->
            options.sdf = sdf
            options.pixelRatio = scale
            stretchPx?.let { px ->
              if (px.stretchX.isNotEmpty()) {
                options.stretchX = px.stretchX.map { (start, end) -> FfiImageStretch(start, end) }
              }
              if (px.stretchY.isNotEmpty()) {
                options.stretchY = px.stretchY.map { (start, end) -> FfiImageStretch(start, end) }
              }
              px.content?.let { box ->
                options.content = ImageContent(box.left, box.top, box.right, box.bottom)
              }
            }
          },
      )
    }
  }

  internal fun imageStretches(id: String): Pair<List<FfiImageStretch>, List<FfiImageStretch>>? =
    binding.readMap { map ->
      map.styleImageStretches(id)
    }

  override fun removeImage(id: String) {
    binding.mutateMap { map -> map.removeStyleImage(id) }
  }

  override fun getSource(id: String): Source? = binding.readMap { map ->
    if (!isStyleSource(map, id)) null else reconstructSource(map, id)
  }

  override fun getSources(): List<Source> =
    binding
      .readMap { map ->
        map.styleSourceIds().filter { isStyleSource(map, it) }.map { reconstructSource(map, it) }
      }
      .orEmpty()

  /** mbgl keeps an annotations source of its own in every style; no other platform has one. */
  private fun isStyleSource(map: MapHandle, id: String): Boolean =
    map.styleSourceExists(id) && map.styleSourceType(id) != SourceType.ANNOTATIONS

  override fun addSource(source: Source) {
    source.attach(binding)
  }

  override fun removeSource(source: Source) {
    source.detach(binding)
  }

  override fun getLayer(id: String): Layer? = binding.readMap { map ->
    if (!map.styleLayerExists(id)) null else reconstructLayer(map, id)
  }

  override fun getLayers(): List<Layer> =
    binding.readMap { map -> map.styleLayerIds().map { reconstructLayer(map, it) } }.orEmpty()

  override fun addLayer(layer: Layer) {
    // MapLibre has no explicit "on top"; an empty anchor means the same thing.
    layer.attach(binding, beforeLayerId = "")
  }

  override fun addLayerAbove(id: String, layer: Layer) {
    // "Above id" is "below whatever currently sits above id", which is the next layer along.
    val anchor =
      binding.readMap { map ->
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
      binding.readMap { map ->
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
   * Rebuilds a source that the base style owns. Only [UnknownSource] is produced: typed source
   * classes still carry construction options MapLibre does not report back, such as GeoJSON cluster
   * settings.
   */
  private fun reconstructSource(map: MapHandle, id: String): Source =
    UnknownSource(id, sourceDefinition(map, id)).also { it.bindExisting(binding) }

  private fun sourceDefinition(map: MapHandle, id: String): JsonObject {
    val info = map.styleSourceInfo(id)
    return buildJsonObject {
      (info?.type ?: map.styleSourceType(id))?.toStyleSpecType()?.let { put("type", it) }
      val attribution =
        info?.attribution?.takeIf { it.isNotEmpty() } ?: declaredAttribution(map, id)
      attribution?.let { put("attribution", it) }
      info?.tileSize?.takeIf { it > 0 }?.let { put("tileSize", it) }
      if (info?.volatileSource == true) put("volatile", true)
      if (info?.type == SourceType.VECTOR) {
        info.vectorEncoding?.toStyleSpecEncoding()?.let { put("encoding", it) }
      }
      if (info?.type == SourceType.RASTER_DEM) {
        info.rasterDemEncoding?.toStyleSpecEncoding()?.let { put("encoding", it) }
      }
      val url = info?.url?.takeIf { it.isNotEmpty() }
      if (url != null) put("url", url) else info?.tileJson?.let { putTileJson(it) }
    }
  }

  private fun JsonObjectBuilder.putTileJson(tileJson: TileJson) {
    if (tileJson.tileUrls.isNotEmpty()) {
      putJsonArray("tiles") { tileJson.tileUrls.forEach { add(it) } }
    }
    put("minzoom", tileJson.minZoom)
    put("maxzoom", tileJson.maxZoom)
    when (tileJson.scheme) {
      TileScheme.XYZ -> put("scheme", "xyz")
      TileScheme.TMS -> put("scheme", "tms")
      else -> Unit
    }
    tileJson.bounds?.toBoundingBox()?.let { box ->
      putJsonArray("bounds") {
        add(box.west)
        add(box.south)
        add(box.east)
        add(box.north)
      }
    }
  }

  /**
   * The attribution the loaded style document declares for [id]. MapLibre neither parses nor
   * reports attribution for GeoJSON and image sources, so the document is the only place it
   * survives.
   */
  private fun declaredAttribution(map: MapHandle, id: String): String? {
    val sources =
      declaredSources
        ?: run {
          val document = runCatching { map.loadedStyleJson().toJsonElement() }.getOrNull()
          ((document as? JsonObject)?.get("sources") as? JsonObject ?: JsonObject(emptyMap()))
            .also { declaredSources = it }
        }
    return ((sources[id] as? JsonObject)?.get("attribution") as? JsonPrimitive)?.contentOrNull
  }

  /** Confined to the map's owner thread, where every read through [binding] runs. */
  private var declaredSources: JsonObject? = null

  private fun reconstructLayer(map: MapHandle, id: String): Layer {
    val definition =
      (map.styleLayerJson(id)?.toJsonElement() as? JsonObject)
        ?: buildJsonObject { map.styleLayerType(id)?.let { put("type", it) } }
    return UnknownLayer(id, definition).also { it.bindExisting(binding) }
  }
}
