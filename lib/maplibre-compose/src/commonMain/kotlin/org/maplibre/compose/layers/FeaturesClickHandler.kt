package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.interaction.ClickEvent
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

/**
 * A callback for when features in a layer are clicked. It runs with the [ClickEvent] as its
 * receiver, so it can read where the click happened.
 *
 * The features are the layer's features within the layer's `hitPadding` of the click, in render
 * order, front first. The map calls the handler only when the click hits at least one of them, so
 * the list is never empty. Feature geometries keep the coordinates the engine rendered; see
 * [org.maplibre.compose.map.MapState.queryRenderedFeatures].
 *
 * When a double tap could also respond, as with the default double-tap zoom, a touch tap reaches
 * `onClick` only after the double-tap timeout, because the map waits for a possible second tap.
 *
 * @return [ClickResult.Consume] if this click should be consumed and not passed down to layers
 *   rendered below this one or [ClickResult.Pass] if it should be passed down.
 */
public typealias FeaturesClickHandler =
  ClickEvent.(features: List<Feature<Geometry, JsonObject?>>) -> ClickResult
