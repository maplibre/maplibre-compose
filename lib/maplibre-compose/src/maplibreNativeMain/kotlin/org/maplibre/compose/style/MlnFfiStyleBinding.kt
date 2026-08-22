package org.maplibre.compose.style

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.sources.MlnFfiFeatureStateStore
import org.maplibre.compose.util.toJsonBytes
import org.maplibre.compose.util.toJsonElement
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.RenderSessionHandle

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

  /**
   * Reports that [sourceId] was added or removed, so StyleState can refresh that source without
   * waiting for idle. Call only from the owner thread, after the native add or remove.
   */
  fun reportSourceChanged(sourceId: String) {}

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

        override fun <T> readMap(action: (MapHandle) -> T): T? = null

        override fun <T> mutateMap(abandon: () -> Unit, action: (MapHandle) -> T): T? {
          abandon()
          return null
        }

        override fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T? = null
      }
  }
}
