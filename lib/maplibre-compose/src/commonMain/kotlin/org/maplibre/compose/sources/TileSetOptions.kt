package org.maplibre.compose.sources

import androidx.compose.runtime.Immutable
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * Options for a tiled source, applied to its TileJSON metadata.
 *
 * @param minZoom Minimum zoom level at which tiles are available, as in the TileJSON `minzoom`.
 * @param maxZoom Maximum zoom level at which tiles are available, as in the TileJSON `maxzoom`.
 * @param tileCoordinateSystem The tiling scheme the tile URLs follow.
 * @param boundingBox The extent the tiles cover, as in the TileJSON `bounds`. Longitudes follow the
 *   TileJSON convention and stay within ±180°.
 * @param attributionHtml Attribution shown for content from this source.
 */
@Immutable
public data class TileSetOptions(
  val minZoom: Int = SourceDefaults.MIN_ZOOM,
  val maxZoom: Int = SourceDefaults.MAX_ZOOM,
  val tileCoordinateSystem: TileCoordinateSystem = TileCoordinateSystem.XYZ,
  val boundingBox: BoundingBox? = null,
  val attributionHtml: String? = null,
)
