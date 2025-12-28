@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.stylespec.sources

/**
 * Property to use as a feature ID (for feature state) in [GeoJSONSourceSpecification] or
 * [VectorSourceSpecification].
 *
 * Can be either a simple property name (string) or an object mapping source layer names to property
 * names.
 *
 * See [MapLibre Style Spec](https://maplibre.org/maplibre-style-spec/sources/#promoteid).
 */
public external interface PromoteIdDefinition
