@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.stylespec.sources

/** [See MapLibre Style Spec.](https://maplibre.org/maplibre-style-spec/sources/#raster) */
public external interface RasterSourceSpecification : SourceSpecification {

  /** A URL to a TileJSON resource. Supported protocols are http: and https:. */
  public var url: String?

  /** An array of one or more tile source URLs, as in TileJSON spec. */
  public var tiles: Array<String>?

  /**
   * An array containing longitude and latitude of southwest and northeast corners of source's
   * bounding box in following order: [sw.lng, sw.lat, ne.lng, ne.lat]. When this property is
   * included in a source, no tiles outside of given bounds are requested by MapLibre. Defaults to
   * [-180, -85.051129, 180, 85.051129]
   */
  public var bounds: Array<Double>?

  /** Minimum zoom level for which tiles are available, as in TileJSON spec. */
  public var minzoom: Int?

  /**
   * Maximum zoom level for which tiles are available, as in TileJSON spec. Data from tiles at
   * maxzoom are used when displaying map at higher zoom levels. Defaults to 22
   */
  public var maxzoom: Int?

  /**
   * The minimum visual size to display tiles for this layer. Only configurable for raster layers.
   * Defaults to 512
   */
  public var tileSize: Int?

  /**
   * Influences the y direction of the tile coordinates. The global-mercator (aka Spherical
   * Mercator) profile is assumed. Defaults to "xyz"
   */
  public var scheme: String?

  /** Contains an attribution to be displayed when map is shown to a user. */
  public var attribution: String?

  /** A setting to determine whether a source's tiles are cached locally. */
  public var volatile: Boolean?
}
