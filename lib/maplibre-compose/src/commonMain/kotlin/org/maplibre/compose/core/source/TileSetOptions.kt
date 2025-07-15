package org.maplibre.compose.core.source

import androidx.compose.runtime.Immutable
import io.github.dellisd.spatialk.geojson.BoundingBox

@Immutable
public data class TileSetOptions(
  val minZoom: Int = Defaults.MIN_ZOOM,
  val maxZoom: Int = Defaults.MAX_ZOOM,
  val tileCoordinateSystem: TileCoordinateSystem = TileCoordinateSystem.XYZ,
  val boundingBox: BoundingBox? = null,
  val attributionHtml: String? = null,
)
