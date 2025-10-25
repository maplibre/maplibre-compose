package org.maplibre.compose.util

import kotlinx.serialization.json.JsonObject
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

/**
 * A callback for when a feature is clicked.
 *
 * @return [ClickResult.Consume] if this click should be consumed and not passed down to layers
 *   rendered below this one or [ClickResult.Pass] if it should be passed down.
 */
public typealias FeaturesClickHandler = (List<Feature<Geometry, JsonObject?>>) -> ClickResult
