package org.maplibre.compose.sources

import androidx.compose.runtime.Immutable
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * The TileJSON fields of a tiled source.
 *
 * @param minZoom Minimum zoom level for which tiles are available. Defaults to 0.
 * @param maxZoom Maximum zoom level for which tiles are available. MapLibre overzooms the highest
 *   tiles beyond it. Defaults to 22, the style spec default for vector, raster, and raster-dem
 *   sources.
 * @param scheme How the tile URLs number tile rows. Defaults to [TileScheme.Xyz].
 * @param boundingBox The area that has tiles. MapLibre requests no tiles outside it. Null means the
 *   whole world.
 * @param attributionHtml Attribution shown for this source, as HTML.
 */
@Immutable
public data class TileSetOptions(
  val minZoom: Int = 0,
  val maxZoom: Int = 22,
  val scheme: TileScheme = TileScheme.Xyz,
  val boundingBox: BoundingBox? = null,
  val attributionHtml: String? = null,
)
