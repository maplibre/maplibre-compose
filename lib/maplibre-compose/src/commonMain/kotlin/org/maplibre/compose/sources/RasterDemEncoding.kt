package org.maplibre.compose.sources

import kotlin.jvm.JvmInline

/**
 * How the tiles of a [RasterDemTileSource] store elevation in pixel colors: the style spec's
 * `encoding` source property.
 *
 * MapLibre reads the elevation of each pixel, in meters, as `red * redFactor + green *
 * greenFactor + blue * blueFactor - baseShift`, where each channel is 0 to 255. Each encoding fixes
 * these four numbers, except [Custom], which takes them from the source.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 *
 * @property value The style spec's name for this encoding, such as `mapbox`.
 */
@JvmInline
public value class RasterDemEncoding private constructor(public val value: String) {
  public companion object {
    /**
     * Mapbox Terrain RGB tiles. See
     * https://www.mapbox.com/help/access-elevation-data/#mapbox-terrain-rgb for more info.
     */
    public val Mapbox: RasterDemEncoding = RasterDemEncoding("mapbox")

    /**
     * Terrarium format PNG tiles. See https://aws.amazon.com/es/public-datasets/terrain/ for more
     * info.
     */
    public val Terrarium: RasterDemEncoding = RasterDemEncoding("terrarium")

    /**
     * Tiles decoded with the `redFactor`, `greenFactor`, `blueFactor`, and `baseShift` that the
     * [RasterDemTileSource] was given.
     *
     * MapLibre Native doesn't support this encoding
     * ([#2783](https://github.com/maplibre/maplibre-native/issues/2783)). On Android, iOS, and
     * desktop, the source uses [Mapbox] instead and ignores the factors.
     */
    public val Custom: RasterDemEncoding = RasterDemEncoding("custom")
  }
}
