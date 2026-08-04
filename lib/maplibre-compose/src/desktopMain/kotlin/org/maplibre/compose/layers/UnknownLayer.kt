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
   * Replays the whole reported object rather than the few keys a layer usually carries.
   *
   * Every key matters because losing one is silent. A base layer restored without its `filter`
   * draws everything the style wrote that filter to exclude, and one restored without its
   * `source-layer` selects nothing from a vector source and draws nothing at all; neither reports
   * an error anywhere. Naming keys individually is how that happened, so this names only the three
   * that are handled elsewhere and passes everything else through.
   *
   * `id`, `type`, and `source` are those three: [toJson] writes them from this layer's own fields.
   *
   * The style spec's `metadata` is not among what arrives. MapLibre parses it and then does not
   * keep it — a layer that declares metadata in the style JSON serializes back without it — so a
   * restored layer cannot carry metadata no matter what this replays.
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
