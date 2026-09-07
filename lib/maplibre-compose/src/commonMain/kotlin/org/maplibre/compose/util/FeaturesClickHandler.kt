package org.maplibre.compose.util

import kotlinx.serialization.json.JsonObject
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

/**
 * A callback for when a feature is clicked.
 *
 * Feature geometries carry the coordinates the engine queried, exactly as rendered: they are not
 * normalized, a geometry that crosses the antimeridian may be split into pieces, and its longitudes
 * may fall outside ±180° in either direction.
 *
 * @return [ClickResult.Consume] if this click should be consumed and not passed down to layers
 *   rendered below this one or [ClickResult.Pass] if it should be passed down.
 */
public typealias FeaturesClickHandler = (List<Feature<Geometry, JsonObject?>>) -> ClickResult
