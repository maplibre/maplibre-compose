package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

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

  // TODO(maplibre-compose): `source-layer`, `filter`, and `metadata` are dropped, because [Layer]
  //   exposes no protected way to write a root key and its filter setter takes a compiled
  //   expression rather than JSON. Restoring a replaced base layer that had either one restores it
  //   unfiltered and, over a vector source, empty. The full definition is kept here so this can be
  //   fixed without changing what the style reads.
  init {
    (definition["minzoom"] as? JsonPrimitive)?.floatOrNull?.let { minZoom = it }
    (definition["maxzoom"] as? JsonPrimitive)?.floatOrNull?.let { maxZoom = it }
    (definition["layout"] as? JsonObject)?.forEach { (name, value) ->
      setLayoutProperty(name, value)
    }
    (definition["paint"] as? JsonObject)?.forEach { (name, value) -> setPaintProperty(name, value) }
  }
}
