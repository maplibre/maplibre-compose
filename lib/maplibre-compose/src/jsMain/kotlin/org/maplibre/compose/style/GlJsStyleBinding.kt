package org.maplibre.compose.style

import co.touchlab.kermit.Logger
import js.objects.unsafeJso
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.gljs.FilterSpecification
import org.maplibre.compose.gljs.GlJsSubscription
import org.maplibre.compose.gljs.LayerSpecification
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.QuerySourceFeatureOptions
import org.maplibre.compose.gljs.SourceHandle
import org.maplibre.compose.gljs.SourceSpecification
import org.maplibre.compose.gljs.subscribe
import org.maplibre.compose.sources.featureIdentifiers
import org.maplibre.compose.sources.toJsonObjectOrEmpty
import org.maplibre.compose.util.toGeoJsonFeature
import org.maplibre.compose.util.toJsValue
import org.maplibre.compose.util.toJsonElement
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

/** [StyleBinding] over a MapLibre GL JS map. One binding belongs to one loaded style. */
internal class GlJsStyleBinding(private val map: MaplibreMap, override val logger: Logger?) :
  StyleBinding {

  private var loaded = true
  private val unloadActions = mutableSetOf<() -> Unit>()

  /**
   * GL JS reports a style change it will not make by firing an `error` event rather than throwing,
   * so a mutation's outcome is read by watching this across the call.
   */
  private var errorCount = 0
  private var lastError: String? = null

  internal val lastReportedError: String?
    get() = lastError

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
    val actions = unloadActions.toList()
    unloadActions.clear()
    actions.forEach { it() }
  }

  override fun onUnload(action: () -> Unit): () -> Unit {
    if (!loaded) {
      action()
      return {}
    }
    unloadActions += action
    return { unloadActions -= action }
  }

  override val supportsCustomDemEncoding: Boolean = true

  /** GL JS rejects a raster-dem source that carries a `scheme`, and reads only XYZ tiles. */
  override val supportsRasterDemScheme: Boolean = false

  override fun addSource(sourceId: String, source: JsonObject): Boolean {
    if (!loaded) return false
    mutate("add source '$sourceId'") {
      map.addSource(sourceId, source.toJsValue<SourceSpecification>())
    }
    return true
  }

  override fun removeSource(sourceId: String) {
    if (!loaded) return
    map.removeSource(sourceId)
  }

  override fun sourceExists(sourceId: String): Boolean? =
    if (!loaded) null else map.getSource<SourceHandle>(sourceId) != null

  override fun setFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    state: JsonObject,
  ) {
    if (!loaded) return
    val js = state.toJsValue<Any>()
    for (ident in featureIdentifiers(sourceId, sourceLayerId, featureId)) {
      map.setFeatureState(ident, js)
    }
  }

  /**
   * Merged across the identifier forms: MapLibre keys state by the feature id's JS type, and a
   * feature the common API names as text may be stored under either.
   */
  override fun featureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
  ): JsonObject {
    if (!loaded) return JsonObject(emptyMap())
    var merged = JsonObject(emptyMap())
    for (ident in featureIdentifiers(sourceId, sourceLayerId, featureId)) {
      val next = map.getFeatureState(ident).toJsonObjectOrEmpty()
      if (next.isNotEmpty()) merged = JsonObject(merged + next)
    }
    return merged
  }

  override fun removeFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  ) {
    if (!loaded) return
    for (ident in featureIdentifiers(sourceId, sourceLayerId, featureId)) {
      if (stateKey == null) map.removeFeatureState(ident)
      else map.removeFeatureState(ident, stateKey)
    }
  }

  override fun resetFeatureStates(sourceId: String, sourceLayerId: String?) {
    if (!loaded) return
    for (ident in featureIdentifiers(sourceId, sourceLayerId, featureId = null)) {
      map.removeFeatureState(ident)
    }
  }

  /** MapLibre GL JS queries one source layer per call, where the common contract takes a set. */
  override fun querySourceFeatures(
    sourceId: String,
    sourceLayerIds: Set<String>,
    filter: JsonElement?,
  ): List<Feature<Geometry, JsonObject?>> {
    if (!loaded || sourceLayerIds.isEmpty()) return emptyList()
    val js = filter?.toJsValue<FilterSpecification>()
    return sourceLayerIds.flatMap { layer ->
      val options =
        unsafeJso<QuerySourceFeatureOptions> {
          sourceLayer = layer
          this.filter = js
        }
      map.querySourceFeatures(sourceId, options).map { it.toGeoJsonFeature() }
    }
  }

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
