package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.UnknownLayer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.UnknownSource
import org.maplibre.compose.util.ImageResizeOptions
import org.maplibre.kmp.native.map.MapLibreMap

internal class DesktopStyle(internal val impl: MapLibreMap) : Style {

  // Cache layer JSON specs from the base style so they can be re-inserted later (e.g. Anchor.Replace)
  private val baseLayerSpecs: Map<String, String>

  // Track user-added layers and sources by id
  private val userLayers = mutableMapOf<String, Layer>()
  private val userSources = mutableMapOf<String, Source>()

  init {
    val styleJson = impl.getStyleJson()
    baseLayerSpecs = parseLayerSpecs(styleJson)
  }

  // region Images

  override fun addImage(
    id: String,
    image: ImageBitmap,
    sdf: Boolean,
    resizeOptions: ImageResizeOptions?,
  ) {
    val w = image.width
    val h = image.height
    val pixelMap = image.toPixelMap()
    // Convert ARGB (compose) to RGBA premultiplied (mbgl)
    val bytes = ByteArray(w * h * 4)
    var i = 0
    for (y in 0 until h) {
      for (x in 0 until w) {
        val c = pixelMap[x, y]
        val a = c.alpha
        bytes[i++] = (c.red * a * 255 + 0.5f).toInt().coerceIn(0, 255).toByte()
        bytes[i++] = (c.green * a * 255 + 0.5f).toInt().coerceIn(0, 255).toByte()
        bytes[i++] = (c.blue * a * 255 + 0.5f).toInt().coerceIn(0, 255).toByte()
        bytes[i++] = (a * 255 + 0.5f).toInt().coerceIn(0, 255).toByte()
      }
    }
    val contentLeft = resizeOptions?.left?.value ?: 0f
    val contentTop = resizeOptions?.top?.value ?: 0f
    val contentRight = if (resizeOptions != null) w - resizeOptions.right.value else w.toFloat()
    val contentBottom = if (resizeOptions != null) h - resizeOptions.bottom.value else h.toFloat()
    impl.addStyleImage(id, w, h, bytes, 1f, sdf, contentLeft, contentTop, contentRight, contentBottom)
  }

  override fun removeImage(id: String) {
    impl.removeStyleImage(id)
  }

  // endregion

  // region Sources

  override fun getSource(id: String): Source? =
    userSources[id] ?: run {
      val ids = impl.getSourceIds()
      if (id in ids) UnknownSource(id, "{}") else null
    }

  override fun getSources(): List<Source> =
    impl.getSourceIds().map { userSources[it] ?: UnknownSource(it, "{}") }

  override fun addSource(source: Source) {
    impl.addSourceJson(source.id, source.toJson())
    userSources[source.id] = source
    if (source is org.maplibre.compose.sources.GeoJsonSource) source.style = this
    if (source is org.maplibre.compose.sources.ImageSource) source.style = this
  }

  override fun removeSource(source: Source) {
    impl.removeSource(source.id)
    userSources.remove(source.id)
    if (source is org.maplibre.compose.sources.GeoJsonSource) source.style = null
    if (source is org.maplibre.compose.sources.ImageSource) source.style = null
  }

  /** Called by GeoJsonSource.setData() to update GeoJSON data in-place without removing the source. */
  internal fun updateGeoJsonData(source: org.maplibre.compose.sources.GeoJsonSource, geoJson: String) {
    impl.setGeoJsonData(source.id, geoJson)
  }

  /** Called by source setImage()/setUri() to re-add the source with updated data. Only safe when no layers reference the source. */
  internal fun updateSource(source: Source) {
    impl.removeSource(source.id)
    impl.addSourceJson(source.id, source.toJson())
  }

  // endregion

  // region Layers

  override fun getLayer(id: String): Layer? {
    userLayers[id]?.let { return it }
    val spec = baseLayerSpecs[id] ?: return null
    return UnknownLayer(id, spec)
  }

  override fun getLayers(): List<Layer> =
    impl.getLayerIds().map { userLayers[it] ?: UnknownLayer(it, baseLayerSpecs[it] ?: "{}") }

  override fun addLayer(layer: Layer) {
    impl.addLayerJson(layer.toJson(), null)
    layer.style = this
    layer.insertedBefore = null
    userLayers[layer.id] = layer
  }

  override fun addLayerAbove(id: String, layer: Layer) {
    // In mbgl, addLayerJson(json, beforeId) inserts BELOW beforeId.
    // "above id" means we need the layer that comes after id in the stack, and use it as beforeId.
    val allIds = impl.getLayerIds()
    val idx = allIds.indexOf(id)
    val beforeId = if (idx < 0 || idx == allIds.size - 1) null else allIds[idx + 1]
    impl.addLayerJson(layer.toJson(), beforeId)
    layer.style = this
    layer.insertedBefore = beforeId
    userLayers[layer.id] = layer
  }

  override fun addLayerBelow(id: String, layer: Layer) {
    impl.addLayerJson(layer.toJson(), id)
    layer.style = this
    layer.insertedBefore = id
    userLayers[layer.id] = layer
  }

  override fun addLayerAt(index: Int, layer: Layer) {
    val allIds = impl.getLayerIds()
    // index 0 = bottom (below everything), index = allIds.size means top
    val beforeId = if (index < allIds.size) allIds[index] else null
    impl.addLayerJson(layer.toJson(), beforeId)
    layer.style = this
    layer.insertedBefore = beforeId
    userLayers[layer.id] = layer
  }

  override fun removeLayer(layer: Layer) {
    impl.removeLayer(layer.id)
    userLayers.remove(layer.id)
    layer.style = null
  }

  /** Called by Layer property setters to re-apply the full layer JSON after a property change. */
  internal fun updateLayer(layer: Layer) {
    impl.removeLayer(layer.id)
    impl.addLayerJson(layer.toJson(), layer.insertedBefore)
  }

  // endregion

  companion object {
    /** Parses the top-level `layers` array from a MapLibre style JSON and returns a map of id → json. */
    private fun parseLayerSpecs(styleJson: String): Map<String, String> {
      if (styleJson.isBlank()) return emptyMap()
      return try {
        // Minimal JSON parser to extract layer objects from the "layers" array.
        // We rely on the fact that each layer JSON is a valid JSON object at the top level.
        val result = mutableMapOf<String, String>()
        val layersKey = "\"layers\""
        val layersStart = styleJson.indexOf(layersKey)
        if (layersStart < 0) return emptyMap()
        val bracketStart = styleJson.indexOf('[', layersStart + layersKey.length)
        if (bracketStart < 0) return emptyMap()
        var pos = bracketStart + 1
        val end = styleJson.length
        while (pos < end) {
          // skip whitespace and commas
          while (pos < end && styleJson[pos].isWhitespace()) pos++
          if (pos >= end) break
          if (styleJson[pos] == ']') break
          if (styleJson[pos] == ',') { pos++; continue }
          if (styleJson[pos] == '{') {
            val objEnd = findMatchingBrace(styleJson, pos)
            val objJson = styleJson.substring(pos, objEnd + 1)
            val id = extractJsonStringField(objJson, "id")
            if (id != null) result[id] = objJson
            pos = objEnd + 1
          } else {
            pos++
          }
        }
        result
      } catch (e: Exception) {
        emptyMap()
      }
    }

    /** Finds the closing `}` matching the `{` at [start]. */
    private fun findMatchingBrace(s: String, start: Int): Int {
      var depth = 0
      var inString = false
      var escape = false
      for (i in start until s.length) {
        val c = s[i]
        if (escape) { escape = false; continue }
        if (c == '\\' && inString) { escape = true; continue }
        if (c == '"') { inString = !inString; continue }
        if (inString) continue
        when (c) {
          '{' -> depth++
          '}' -> { depth--; if (depth == 0) return i }
        }
      }
      return s.length - 1
    }

    /** Extracts the string value of a top-level field from a JSON object string. */
    private fun extractJsonStringField(json: String, field: String): String? {
      val key = "\"$field\""
      var pos = json.indexOf(key)
      while (pos >= 0) {
        val colon = json.indexOf(':', pos + key.length)
        if (colon < 0) return null
        var valStart = colon + 1
        while (valStart < json.length && json[valStart].isWhitespace()) valStart++
        if (valStart >= json.length || json[valStart] != '"') {
          pos = json.indexOf(key, pos + 1)
          continue
        }
        val sb = StringBuilder()
        var i = valStart + 1
        var esc = false
        while (i < json.length) {
          val c = json[i]
          if (esc) { sb.append(c); esc = false }
          else if (c == '\\') esc = true
          else if (c == '"') break
          else sb.append(c)
          i++
        }
        return sb.toString()
      }
      return null
    }
  }
}
