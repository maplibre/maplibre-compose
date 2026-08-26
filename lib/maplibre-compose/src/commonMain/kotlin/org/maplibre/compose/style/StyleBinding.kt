package org.maplibre.compose.style

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

/**
 * A source or layer's connection to its live style, as an ID and style-spec JSON. A binding stops
 * working when its style unloads, after which mutations are dropped and reads answer null rather
 * than throwing.
 */
internal interface StyleBinding {
  val isLoaded: Boolean

  val logger: Logger?

  /** Runs [action] when this style unloads and returns a function that removes the action. */
  fun onUnload(action: () -> Unit): () -> Unit

  /**
   * Adds a complete layer object directly below [beforeLayerId], or on top when that is empty.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if MapLibre refuses the layer.
   */
  fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean

  fun removeLayer(layerId: String)

  /** Moves a layer to sit directly below [beforeLayerId], or on top when that is empty. */
  fun moveLayer(layerId: String, beforeLayerId: String)

  /**
   * Sets one property on a layer that is already in the style.
   *
   * @param kind which of a layer object's three parts [name] belongs to; a name offered to the
   *   wrong one is rejected.
   * @throws StyleMutationException if MapLibre refuses [value]; the layer keeps its previous one.
   */
  fun setLayerProperty(layerId: String, name: String, value: JsonElement, kind: LayerPropertyKind)

  fun setLayerFilter(layerId: String, filter: JsonElement)

  /** @return null if the style has unloaded, or the layer holds no value for [name]. */
  fun layerProperty(layerId: String, name: String): JsonElement?

  /**
   * Reports whether a live layer with [layerId] is in the style, so a duplicate can be refused on
   * the caller with the message that names the cause.
   *
   * @return null if the style has unloaded or the answer could not be determined; the add still
   *   refuses a duplicate in that case.
   */
  fun layerExists(layerId: String): Boolean?

  /**
   * Why this engine cannot take a layer property, or null when it can. The style spec is shared,
   * but each engine implements it at its own pace; a non-null reason keeps the property out of
   * every write to this binding, and the layer reports it once instead.
   */
  fun unsupportedLayerPropertyReason(layerType: String, name: String): String? = null

  /**
   * Adds a source from its style-spec definition.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if MapLibre refuses the source.
   */
  fun addSource(sourceId: String, source: JsonObject): Boolean

  /** Removes a source and forgets the feature state that belonged to it. */
  fun removeSource(sourceId: String)

  /**
   * Reports whether a live source with [sourceId] is in the style, so a duplicate can be refused on
   * the caller with the message that names the cause.
   *
   * @return null if the style has unloaded or the answer could not be determined; the add still
   *   refuses a duplicate in that case.
   */
  fun sourceExists(sourceId: String): Boolean?

  /**
   * Reports that [sourceId] was added or removed, so the style state can refresh that source
   * without waiting for idle. An engine that adds sources through its own hop calls this from
   * inside that hop, after the add or remove.
   */
  fun reportSourceChanged(sourceId: String) {}

  /** Merges [state] into the state of one feature; a null value in [state] drops that key. */
  fun setFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    state: JsonObject,
  )

  /** @return an empty object when the feature has no state, or the style has unloaded. */
  fun featureState(sourceId: String, sourceLayerId: String?, featureId: String): JsonObject

  /** Removes one key of a feature's state, or the whole state when [stateKey] is null. */
  fun removeFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  )

  /** Removes the state of every feature in a source, or in one of its source layers. */
  fun resetFeatureStates(sourceId: String, sourceLayerId: String?)

  /**
   * Queries the features a source has loaded, whether or not they are drawn.
   *
   * @param filter a style-spec filter expression, or null to match every feature.
   * @return empty when the style has unloaded or nothing has rendered yet.
   */
  fun querySourceFeatures(
    sourceId: String,
    sourceLayerIds: Set<String>,
    filter: JsonElement?,
  ): List<Feature<Geometry, JsonObject?>>

  companion object {
    /** A binding for a descriptor that has never been added to a style. */
    val UNLOADED: StyleBinding =
      object : StyleBinding {
        override val isLoaded: Boolean = false

        override val logger: Logger? = null

        override fun onUnload(action: () -> Unit): () -> Unit {
          action()
          return {}
        }

        override fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean = false

        override fun removeLayer(layerId: String) = Unit

        override fun moveLayer(layerId: String, beforeLayerId: String) = Unit

        override fun setLayerProperty(
          layerId: String,
          name: String,
          value: JsonElement,
          kind: LayerPropertyKind,
        ) = Unit

        override fun setLayerFilter(layerId: String, filter: JsonElement) = Unit

        override fun layerProperty(layerId: String, name: String): JsonElement? = null

        override fun layerExists(layerId: String): Boolean? = null

        override fun addSource(sourceId: String, source: JsonObject): Boolean = false

        override fun removeSource(sourceId: String) = Unit

        override fun sourceExists(sourceId: String): Boolean? = null

        override fun setFeatureState(
          sourceId: String,
          sourceLayerId: String?,
          featureId: String,
          state: JsonObject,
        ) = Unit

        override fun featureState(
          sourceId: String,
          sourceLayerId: String?,
          featureId: String,
        ): JsonObject = JsonObject(emptyMap())

        override fun removeFeatureState(
          sourceId: String,
          sourceLayerId: String?,
          featureId: String,
          stateKey: String?,
        ) = Unit

        override fun resetFeatureStates(sourceId: String, sourceLayerId: String?) = Unit

        override fun querySourceFeatures(
          sourceId: String,
          sourceLayerIds: Set<String>,
          filter: JsonElement?,
        ): List<Feature<Geometry, JsonObject?>> = emptyList()
      }
  }
}

/** Which part of a layer object a property belongs to. */
internal enum class LayerPropertyKind {
  LAYOUT,
  PAINT,

  /** A key on the layer object itself, such as `minzoom`, rather than in `layout` or `paint`. */
  ROOT,
}

/** A style mutation MapLibre refused. */
internal class StyleMutationException(message: String?, cause: Throwable?) :
  RuntimeException(message, cause)
