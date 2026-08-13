package org.maplibre.compose.style

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toFfiJsonValue
import org.maplibre.compose.util.toJsonElement
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.RenderSessionHandle

/**
 * [StyleBinding] over a MapLibre Native map. Every `MapHandle` call has to run on the owner thread;
 * the session supplies that hop.
 */
internal interface MlnFfiStyleBinding : StyleBinding {
  /** Null if the style has unloaded; reads should then fall back to the descriptor. */
  fun <T> readMap(action: (MapHandle) -> T): T?

  /** Requests a repaint after native accepts the mutation. */
  fun <T> mutateMap(action: (MapHandle) -> T): T?

  /**
   * Null when the style has unloaded or no session is attached yet — a session exists only between
   * the first frame and teardown. The handle must not escape [action].
   */
  fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T?

  override fun attachSource(source: Source) {
    source.attach(this)
  }

  override fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean =
    mutateMap { map ->
      try {
        map.addStyleLayerJson(layer.toFfiJsonValue(), beforeLayerId)
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
        map.setLayerProperty(layerId, name, value.toFfiJsonValue())
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
    }
  }

  override fun setLayerFilter(layerId: String, filter: JsonElement) {
    mutateMap { map -> map.setLayerFilter(layerId, filter.toFfiJsonValue()) }
  }

  override fun layerProperty(layerId: String, name: String): JsonElement? = readMap { map ->
    map.layerProperty(layerId, name)?.toJsonElement()
  }

  companion object {
    /** A binding for a descriptor that has never been added to a style. */
    val UNLOADED: MlnFfiStyleBinding =
      object : MlnFfiStyleBinding {
        override val isLoaded: Boolean = false

        override val logger: Logger? = null

        override fun <T> readMap(action: (MapHandle) -> T): T? = null

        override fun <T> mutateMap(action: (MapHandle) -> T): T? = null

        override fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T? = null
      }
  }
}

internal actual fun mapLibreImplementsLayerProperty(name: String): Boolean = true
