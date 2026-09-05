package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.round
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
  val center =
    latLngsForPixels(listOf(ScreenPoint(size.width / 2.0, size.height / 2.0))).first().toPosition()
  // mbgl wraps unprojected longitudes to ±180, so a viewport astride the antimeridian would hull
  // to a box spanning nearly the whole world. Unwrap the corners around the center first; like
  // GL JS, the box may then extend past ±180.
  val unwrapped = corners.map { it.unwrapAround(center) }
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
            longitude = unwrapped.minOf { it.longitude },
            latitude = unwrapped.minOf { it.latitude },
          ),
        northeast =
          Position(
            longitude = unwrapped.maxOf { it.longitude },
            latitude = unwrapped.maxOf { it.latitude },
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
  return latLngsForPixels(
      listOf(
        ScreenPoint(0.0, 0.0),
        ScreenPoint(width, 0.0),
        ScreenPoint(0.0, height),
        ScreenPoint(width, height),
      )
    )
    .map { it.toPosition() }
}

private fun Position.unwrapAround(center: Position): Position {
  val delta = round((center.longitude - longitude) / 360.0) * 360.0
  return if (delta == 0.0) this else Position(longitude = longitude + delta, latitude = latitude)
}
