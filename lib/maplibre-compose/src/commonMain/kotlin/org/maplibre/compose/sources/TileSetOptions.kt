package org.maplibre.compose.sources

import androidx.compose.runtime.Immutable
import org.maplibre.spatialk.geojson.BoundingBox

@Immutable
public data class TileSetOptions(
  val minZoom: Int = SourceDefaults.MIN_ZOOM,
  val maxZoom: Int = SourceDefaults.MAX_ZOOM,
  val tileCoordinateSystem: TileCoordinateSystem = TileCoordinateSystem.XYZ,
  val boundingBox: BoundingBox? = null,
  val attributionHtml: String? = null,
)
