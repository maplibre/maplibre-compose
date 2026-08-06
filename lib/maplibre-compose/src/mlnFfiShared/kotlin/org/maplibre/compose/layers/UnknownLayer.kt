package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A layer that came from the style rather than from the composition, such as a base-style layer.
 *
 * The definition is replayed into the descriptor so the layer can be re-added later: an
 * [Anchor.Replace] takes a base layer out of the style and puts it back when the last replacement
 * leaves.
 *
 * @param definition the layer object as MapLibre reported it, from `styleLayerJson`.
 */
internal actual class UnknownLayer(id: String, internal val definition: JsonObject) : Layer(id) {

  override val type: String = (definition["type"] as? JsonPrimitive)?.content.orEmpty()

  override val sourceId: String? = (definition["source"] as? JsonPrimitive)?.content

  /**
   * Replays every reported key rather than a known list, because dropping one is silent: a layer
   * restored without its `filter` or `source-layer` draws wrongly with no error. Only `id`, `type`,
   * and `source` are skipped, since [toJson] writes those from this layer's own fields.
   *
   * MapLibre does not report `metadata` back, so a restored layer cannot carry it.
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
