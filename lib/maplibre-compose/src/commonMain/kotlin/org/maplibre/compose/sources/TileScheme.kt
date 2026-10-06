package org.maplibre.compose.sources

import kotlin.jvm.JvmInline

/**
 * How the tile URLs of a tiled source number tile rows: the style spec's `scheme` source property.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 *
 * @property value The style spec's name for this scheme, such as `xyz`.
 */
@JvmInline
public value class TileScheme private constructor(public val value: String) {
  public companion object {
    /**
     * The origin is at the top-left (northwest), and y values increase southwards.
     *
     * Mapbox and OpenStreetMap tile servers number tiles this way.
     */
    public val Xyz: TileScheme = TileScheme("xyz")

    /**
     * The origin is at the bottom-left (southwest), and y values increase northwards.
     *
     * Tile servers that follow the Tile Map Service Specification number tiles this way.
     */
    public val Tms: TileScheme = TileScheme("tms")
  }
}
