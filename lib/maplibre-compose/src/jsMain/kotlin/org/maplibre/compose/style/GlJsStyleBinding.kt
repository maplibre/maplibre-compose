package org.maplibre.compose.style

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.gljs.FilterSpecification
import org.maplibre.compose.gljs.GlJsSubscription
import org.maplibre.compose.gljs.LayerSpecification
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.SourceHandle
import org.maplibre.compose.gljs.SourceSpecification
import org.maplibre.compose.gljs.subscribe
import org.maplibre.compose.util.toJsValue
import org.maplibre.compose.util.toJsonElement

/** [StyleBinding] over a MapLibre GL JS map. One binding belongs to one loaded style. */
internal class GlJsStyleBinding(private val map: MaplibreMap, override val logger: Logger?) :
  StyleBinding {

  private var loaded = true

  /**
   * GL JS reports a style change it will not make by firing an `error` event rather than throwing,
   * so a mutation's outcome is read by watching this across the call.
   */
  private var errorCount = 0
  private var lastError: String? = null

  private val errors: GlJsSubscription =
    map.subscribe("error") { event ->
      errorCount++
      lastError = event.error?.message
    }

  override val isLoaded: Boolean
    get() = loaded

  fun unload() {
    if (!loaded) return
    loaded = false
    errors.cancel()
  }

  fun addSource(id: String, definition: JsonObject) {
    if (!loaded) return
    mutate("add source '$id'") { map.addSource(id, definition.toJsValue<SourceSpecification>()) }
  }

  fun removeSource(id: String) {
    if (!loaded) return
    map.removeSource(id)
  }

  fun sourceExists(id: String): Boolean = loaded && map.getSource<SourceHandle>(id) != null

  /** Null once the style has unloaded. */
  fun <T> withMap(action: (MaplibreMap) -> T): T? = if (loaded) action(map) else null

  override fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean {
    if (!loaded) return false
    mutate("add layer") {
      val spec = layer.toJsValue<LayerSpecification>()
      // MapLibre reads an absent `beforeId` as "on top"; an empty string is a layer id it will not
      // find.
      if (beforeLayerId.isEmpty()) map.addLayer(spec) else map.addLayer(spec, beforeLayerId)
    }
    return true
  }

  override fun removeLayer(layerId: String) {
    if (!loaded) return
    map.removeLayer(layerId)
  }

  override fun moveLayer(layerId: String, beforeLayerId: String) {
    if (!loaded) return
    if (beforeLayerId.isEmpty()) map.moveLayer(layerId) else map.moveLayer(layerId, beforeLayerId)
  }

  override fun setLayerProperty(
    layerId: String,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ) {
    if (!loaded) return
    val js = value.toJsValue<Any?>()
    mutate("set '$name' on layer '$layerId'") {
      when (kind) {
        LayerPropertyKind.LAYOUT -> map.setLayoutProperty(layerId, name, js)
        LayerPropertyKind.PAINT -> map.setPaintProperty(layerId, name, js)
        LayerPropertyKind.ROOT -> setRootProperty(layerId, name, value)
      }
    }
  }

  /**
   * GL JS fixes a layer's own keys at construction, except the zoom range, which moves as a pair —
   * so the half that was not asked for is read back off the live layer.
   */
  private fun setRootProperty(layerId: String, name: String, value: JsonElement) {
    val number = (value as? JsonPrimitive)?.content?.toDoubleOrNull()
    val layer = map.getLayer(layerId)
    if (number == null || layer == null || (name != "minzoom" && name != "maxzoom")) {
      logger?.w {
        "Layer '$layerId' cannot change '$name' once it is in the style; MapLibre GL JS fixes it " +
          "at construction."
      }
      return
    }
    val minZoom = if (name == "minzoom") number else layer.minzoom ?: 0.0
    val maxZoom = if (name == "maxzoom") number else layer.maxzoom ?: 24.0
    map.setLayerZoomRange(layerId, minZoom, maxZoom)
  }

  override fun setLayerFilter(layerId: String, filter: JsonElement) {
    if (!loaded) return
    // The style spec has no null filter; absent means "match every feature".
    val js = if (filter is JsonNull) null else filter.toJsValue<FilterSpecification>()
    mutate("set the filter on layer '$layerId'") { map.setFilter(layerId, js) }
  }

  /**
   * Trying paint before layout is safe: the style spec gives no layer type a name in both. MapLibre
   * throws rather than answering for a name it does not have.
   */
  override fun layerProperty(layerId: String, name: String): JsonElement? {
    if (!loaded || map.getLayer(layerId) == null) return null
    val value =
      runCatching { map.getPaintProperty(layerId, name) }.getOrNull()
        ?: runCatching { map.getLayoutProperty(layerId, name) }.getOrNull()
    return value?.toJsonElement()
  }

  override fun layerExists(layerId: String): Boolean? =
    if (!loaded) null else map.getLayer(layerId) != null

  private inline fun mutate(what: String, action: () -> Unit) {
    val before = errorCount
    try {
      action()
    } catch (error: Throwable) {
      throw StyleMutationException("MapLibre could not $what: ${error.message}", error)
    }
    if (errorCount != before) {
      throw StyleMutationException("MapLibre could not $what: ${lastError ?: "unknown"}", null)
    }
  }
}
