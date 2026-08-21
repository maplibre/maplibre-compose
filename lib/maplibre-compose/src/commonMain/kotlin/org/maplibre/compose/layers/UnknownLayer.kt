package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A layer that came from the style rather than from the composition, such as a base-style layer.
 *
 * @param definition the layer object as MapLibre reported it, from `styleLayerJson`.
 */
internal class UnknownLayer(id: String, internal val definition: JsonObject) : Layer(id) {

  override val type: String = (definition["type"] as? JsonPrimitive)?.content.orEmpty()

  override val sourceId: String? = (definition["source"] as? JsonPrimitive)?.content

  /**
   * Replays every reported key rather than a known list: a layer restored without its `filter` or
   * `source-layer` draws wrongly with no error. MapLibre does not report `metadata` back, so a
   * restored layer cannot carry it.
   */
  init {
    definition.forEach { (key, value) ->
      when (key) {
        "id",
        "type",
        "source" -> Unit
        "filter" -> setFilterJson(value)
        "layout" -> (value as? JsonObject)?.forEach { (name, v) -> setLayoutProperty(name, v) }
        "paint" -> (value as? JsonObject)?.forEach { (name, v) -> setPaintProperty(name, v) }
        else -> setRootProperty(key, value)
      }
    }
  }
}
