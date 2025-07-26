package org.maplibre.compose.sources

public enum class TileCoordinateSystem {
  /**
   * The origin is at the top-left (northwest), and y values increase southwards.
   *
   * This tile coordinate system is used by Mapbox and OpenStreetMap tile servers.
   */
  XYZ,

  /**
   * The origin is at the bottom-left (southwest), and y values increase northwards.
   *
   * This tile coordinate system is used by tile servers that conform to the Tile Map Service
   * Specification.
   */
  TMS,
}
