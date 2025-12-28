@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.stylespec.sources

/**
 * Represents any source specification type.
 *
 * See [MapLibre Style Spec - Sources](https://maplibre.org/maplibre-style-spec/sources/)
 */
public sealed external interface SourceSpecification {
  /** The source type: "geojson", "vector", "raster", "raster-dem", "image", or "video". */
  public var type: String
}
