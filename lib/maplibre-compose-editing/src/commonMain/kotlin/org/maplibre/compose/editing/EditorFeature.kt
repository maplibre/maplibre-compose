package org.maplibre.compose.editing

import kotlinx.serialization.json.JsonObject
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

/** A feature held by a [FeatureEditorState]. Every stored feature has a non-null id. */
public typealias EditorFeature = Feature<Geometry, JsonObject?>
