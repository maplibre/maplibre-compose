package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.toCameraPosition
import org.maplibre.compose.util.toPosition
import org.maplibre.nativeffi.geo.ScreenPoint
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/** The applied camera and the extents it renders, all read from one map transform. */
internal data class MapViewportGeometry(
  val camera: CameraPosition,
  val size: DpSize,
  val visibleRegion: VisibleRegion,
  val boundingBox: BoundingBox,
)

/** Owner thread only. Reads the camera and extents the map would render right now. */
internal fun MapHandle.readViewportGeometry(): MapViewportGeometry {
  val size = size
  val corners = unprojectedCorners()
  return MapViewportGeometry(
    camera = camera.toCameraPosition(),
    size = DpSize(size.width.dp, size.height.dp),
    visibleRegion =
      VisibleRegion(
        farLeft = corners[0],
        farRight = corners[1],
        nearLeft = corners[2],
        nearRight = corners[3],
      ),
    boundingBox =
      BoundingBox(
        southwest =
          Position(
            longitude = corners.minOf { it.longitude },
            latitude = corners.minOf { it.latitude },
          ),
        northeast =
          Position(
            longitude = corners.maxOf { it.longitude },
            latitude = corners.maxOf { it.latitude },
          ),
      ),
  )
}

/**
 * The map's corners as positions, ordered top-left, top-right, bottom-left, bottom-right.
 *
 * `latLngBoundsForCamera` hulls only the top-left and bottom-right corners, so it misses parts of
 * the viewport whenever the camera is rotated or pitched. Unproject all four corners instead.
 */
private fun MapHandle.unprojectedCorners(): List<Position> {
  val width = size.width.toDouble()
  val height = size.height.toDouble()
  return latLngsForPixelsUnwrapped(
      listOf(
        ScreenPoint(0.0, 0.0),
        ScreenPoint(width, 0.0),
        ScreenPoint(0.0, height),
        ScreenPoint(width, height),
      )
    )
    .map { it.toPosition() }
}
