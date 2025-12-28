@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.stylespec.sources

/**
 * A vector tile source. Tiles must be in Mapbox Vector Tile format. All geometric coordinates in
 * vector tiles must be between `-1 * extent` and `(extent * 2) - 1` inclusive. All layers that use
 * a vector source must specify a `source-layer` value. Note that features are only rendered within
 * their originating tile, which may lead to visual artifacts when large values for width, radius,
 * size or offset are specified. To mitigate rendering issues, either reduce the value of the
 * property causing the artifact or, if you have control over the tile generation process, increase
 * the buffer size to ensure that features are fully rendered within the tile.
 *
 * [See MapLibre Style Spec.](https://maplibre.org/maplibre-style-spec/sources/#vector)
 */
public external interface VectorSourceSpecification : SourceSpecification {
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

  /**
   * Influences the y direction of the tile coordinates. The global-mercator (aka Spherical
   * Mercator) profile is assumed. Defaults to `"xyz"`.
   * - `"xyz"`: Slippy map tilenames scheme.
   * - `"tms"`: OSGeo spec scheme.
   */
  public var scheme: String?

  /** Minimum zoom level for which tiles are available, as in TileJSON spec. */
  public var minzoom: Int?

  /**
   * Maximum zoom level for which tiles are available, as in TileJSON spec. Data from tiles at
   * maxzoom are used when displaying map at higher zoom levels. Defaults to 22
   */
  public var maxzoom: Int?

  /** Contains an attribution to be displayed when map is shown to a user. */
  public var attribution: String?

  /**
   * A property to use as a feature id (for feature state). Either a property name, or an object of
   * form `{<sourceLayer>: <propertyName>}`. If specified as a string for a vector tile source, same
   * property is used across all its source layers.
   */
  public var promoteId: PromoteIdDefinition?

  /** A setting to determine whether a source's tiles are cached locally. Defaults to `false`. */
  public var volatile: Boolean?

  /**
   * The encoding used by this source. Mapbox Vector Tiles encoding is used by default. Defaults to
   * `"mvt"`.
   * - `"mvt"`: Mapbox Vector Tiles. See http://github.com/mapbox/vector-tile-spec for more info.
   * - `"mlt"`: MapLibre Vector Tiles. See https://github.com/maplibre/maplibre-tile-spec for more
   *   info.
   */
  public var encoding: String?
}
