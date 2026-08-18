package org.maplibre.compose.style

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.sources.Source

/**
 * A layer's connection to its live style, as a layer ID and style-spec JSON. A binding stops
 * working when its style unloads, after which mutations are dropped and reads answer null rather
 * than throwing.
 */
internal interface StyleBinding {
  val isLoaded: Boolean

  val logger: Logger?

  /** Adds [source] unless it is already here, reporting its errors as this binding's own. */
  fun attachSource(source: Source)

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

  companion object {
    /** A binding for a descriptor that has never been added to a style. */
    val UNLOADED: StyleBinding =
      object : StyleBinding {
        override val isLoaded: Boolean = false

        override val logger: Logger? = null

        override fun attachSource(source: Source) = Unit

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
