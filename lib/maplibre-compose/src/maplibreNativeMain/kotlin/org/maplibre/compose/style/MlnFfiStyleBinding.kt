package org.maplibre.compose.style

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.sources.MlnFfiFeatureStateStore
import org.maplibre.compose.sources.featureStateSelector
import org.maplibre.compose.sources.forgetFeatureStates
import org.maplibre.compose.sources.liveFeatureStateStore
import org.maplibre.compose.sources.mutateLiveFeatureState
import org.maplibre.compose.util.toGeoJsonFeatures
import org.maplibre.compose.util.toJsonBytes
import org.maplibre.compose.util.toJsonElement
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.query.SourceFeatureQueryOptions
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

/**
 * [StyleBinding] over a MapLibre Native map. Every `MapHandle` call has to run on the owner thread;
 * the session supplies that hop.
 */
internal interface MlnFfiStyleBinding : StyleBinding {
  /** Feature state retained for this loaded style. */
  val featureStateStore: MlnFfiFeatureStateStore?

  /** Null if the style has unloaded; reads should then fall back to the descriptor. */
  fun <T> readMap(action: (MapHandle) -> T): T?

  /** Requests a repaint after native accepts the mutation. */
  fun <T> mutateMap(action: (MapHandle) -> T): T? = mutateMap({}, action)

  /**
   * Requests a repaint after native accepts the mutation.
   *
   * Returns after [action] has run or been dropped. [abandon] runs when [action] will not run.
   */
  fun <T> mutateMap(abandon: () -> Unit, action: (MapHandle) -> T): T?

  /**
   * Null when the style has unloaded or no renderer is ready. The renderer exists after the first
   * successful frame and until teardown. The handle must not escape [action].
   */
  fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T?

  override fun addSource(sourceId: String, source: JsonObject): Boolean =
    addSourceWith(sourceId) { map -> map.addStyleSourceJson(sourceId, source.toJsonBytes()) }

  /**
   * Adds a source on the owner thread, for the types MapLibre Native creates from a typed adder
   * rather than from source JSON. Reports the change and wraps a refusal the way [addSource] does.
   *
   * @return false if the style has unloaded, in which case [add] did not run.
   */
  fun addSourceWith(sourceId: String, add: (MapHandle) -> Unit): Boolean =
    mutateMap { map ->
      try {
        add(map)
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
      reportSourceChanged(sourceId)
    } != null

  override fun removeSource(sourceId: String) {
    mutateMap { map ->
      map.removeStyleSource(sourceId)
      forgetFeatureStates(sourceId)
      reportSourceChanged(sourceId)
    }
  }

  override fun sourceExists(sourceId: String): Boolean? = readMap { map ->
    map.styleSourceExists(sourceId)
  }

  override fun setFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    state: JsonObject,
  ) {
    val store = liveFeatureStateStore() ?: return
    store.set(sourceId, sourceLayerId, featureId, state)
    mutateLiveFeatureState { session ->
      session.setFeatureState(
        featureStateSelector(sourceId, sourceLayerId, featureId),
        state.toJsonBytes(),
      )
    }
  }

  override fun featureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
  ): JsonObject =
    liveFeatureStateStore()?.get(sourceId, sourceLayerId, featureId) ?: JsonObject(emptyMap())

  override fun removeFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  ) {
    val store = liveFeatureStateStore() ?: return
    store.remove(sourceId, sourceLayerId, featureId, stateKey)
    mutateLiveFeatureState { session ->
      session.removeFeatureState(featureStateSelector(sourceId, sourceLayerId, featureId, stateKey))
    }
  }

  override fun resetFeatureStates(sourceId: String, sourceLayerId: String?) {
    val store = liveFeatureStateStore() ?: return
    store.reset(sourceId, sourceLayerId)
    mutateLiveFeatureState { session ->
      session.removeFeatureState(featureStateSelector(sourceId, sourceLayerId))
    }
  }

  /** Empty rather than an exception when no render session is attached. */
  override fun querySourceFeatures(
    sourceId: String,
    sourceLayerIds: Set<String>,
    filter: JsonElement?,
  ): List<Feature<Geometry, JsonObject?>> {
    if (sourceLayerIds.isEmpty()) return emptyList()
    val options =
      SourceFeatureQueryOptions().also {
        it.sourceLayerIds = sourceLayerIds.toList()
        it.filter = filter?.toJsonBytes()
      }
    return withRenderSession { session -> session.querySourceFeatures(sourceId, options) }
      ?.toGeoJsonFeatures()
      .orEmpty()
  }

  override fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean =
    mutateMap { map ->
      try {
        map.addStyleLayerJson(layer.toJsonBytes(), beforeLayerId)
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
    } != null

  override fun removeLayer(layerId: String) {
    mutateMap { map -> map.removeStyleLayer(layerId) }
  }

  override fun moveLayer(layerId: String, beforeLayerId: String) {
    mutateMap { map -> map.moveStyleLayer(layerId, beforeLayerId) }
  }

  /** [kind] is unused: mbgl's `Layer::setProperty` takes layout, paint, and root keys alike. */
  override fun setLayerProperty(
    layerId: String,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ) {
    mutateMap { map ->
      try {
        map.setLayerProperty(layerId, name, value.toJsonBytes())
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
    }
  }

  override fun setLayerFilter(layerId: String, filter: JsonElement) {
    mutateMap { map -> map.setLayerFilter(layerId, filter.toJsonBytes()) }
  }

  override fun layerProperty(layerId: String, name: String): JsonElement? = readMap { map ->
    map.layerProperty(layerId, name)?.toJsonElement()
  }

  override fun layerExists(layerId: String): Boolean? = readMap { map ->
    map.styleLayerIds().contains(layerId)
  }

  override fun unsupportedLayerPropertyReason(layerType: String, name: String): String? =
    UNSUPPORTED_LAYER_PROPERTIES[layerType to name]

  companion object {
    /**
     * Style-spec properties MapLibre Native does not implement; writing one makes it refuse the
     * entire layer. Revisit when bumping the maplibre-native-ffi pin.
     */
    private val UNSUPPORTED_LAYER_PROPERTIES: Map<Pair<String, String>, String> =
      mapOf(
        ("symbol" to "icon-overlap") to
          "MapLibre Native does not implement it. Use iconAllowOverlap instead; note that it " +
            "cannot express the 'cooperative' value.",
        ("symbol" to "text-overlap") to
          "MapLibre Native does not implement it. Use textAllowOverlap instead; note that it " +
            "cannot express the 'cooperative' value.",
        ("fill" to "fill-layer-opacity") to "MapLibre Native does not implement it.",
        ("line" to "line-layer-opacity") to "MapLibre Native does not implement it.",
        ("hillshade" to "resampling") to "MapLibre Native does not implement it.",
        ("color-relief" to "resampling") to "MapLibre Native does not implement it.",
      )

    /** A binding for a descriptor that has never been added to a style. */
    val UNLOADED: MlnFfiStyleBinding =
      object : MlnFfiStyleBinding {
        override val featureStateStore: MlnFfiFeatureStateStore? = null

        override val isLoaded: Boolean = false

        override val logger: Logger? = null

        override fun onUnload(action: () -> Unit): () -> Unit {
          action()
          return {}
        }

        override fun <T> readMap(action: (MapHandle) -> T): T? = null

        override fun <T> mutateMap(abandon: () -> Unit, action: (MapHandle) -> T): T? {
          abandon()
          return null
        }

        override fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T? = null
      }
  }
}
