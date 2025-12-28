@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.stylespec.sources

/**
 * A raster DEM source. Only supports Mapbox Terrain RGB and Mapzen Terrarium tiles.
 *
 * [See MapLibre Style Spec.](https://maplibre.org/maplibre-style-spec/sources/#raster-dem)
 */
public external interface RasterDEMSourceSpecification : SourceSpecification {

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

  /** Contains an attribution to be displayed when map is shown to a user. */
  public var attribution: String?

  /**
   * The encoding used by this source. Mapbox Terrain RGB is used by default. Defaults to "mapbox"
   */
  public var encoding: String?

  /**
   * Value that will be multiplied by red channel value when decoding. Only used on custom
   * encodings. Defaults to 1
   */
  public var redFactor: Double?

  /**
   * Value that will be multiplied by blue channel value when decoding. Only used on custom
   * encodings. Defaults to 1
   */
  public var blueFactor: Double?

  /**
   * Value that will be multiplied by green channel value when decoding. Only used on custom
   * encodings. Defaults to 1
   */
  public var greenFactor: Double?

  /**
   * Value that will be added to encoding mix when decoding. Only used on custom encodings. Defaults
   * to 0
   */
  public var baseShift: Double?
}
