package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.util.VisibleBounds
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.toCameraPosition
import org.maplibre.compose.util.toPosition
import org.maplibre.nativeffi.camera.EdgeInsets
import org.maplibre.nativeffi.geo.ScreenPoint
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapProjectionHandle
import org.maplibre.spatialk.geojson.Position

/** The applied camera and the size it renders at, read from one map transform. */
internal data class MapViewportGeometry(
  val camera: CameraPosition,
  val padding: EdgeInsets,
  val size: DpSize,
)

/**
 * Owner thread only. Reads the camera the map would render right now. Each engine read is one
 * native call, so every field is read once.
 */
internal fun MapHandle.readViewportGeometry(viewportInsets: EdgeInsets): MapViewportGeometry {
  val size = size
  val camera = camera
  return MapViewportGeometry(
    camera = camera.toCameraPosition(viewportInsets),
    padding = camera.padding ?: EdgeInsets.ZERO,
    size = DpSize(size.width.dp, size.height.dp),
  )
}

/** The area a frozen transform renders, derived from its corners. */
internal class MapViewportExtents(corners: List<Position>) {
  val region: VisibleRegion =
    VisibleRegion(
      farLeft = corners[0],
      farRight = corners[1],
      nearLeft = corners[2],
      nearRight = corners[3],
    )

  val bounds: VisibleBounds =
    VisibleBounds(
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
    )
}

/** Answers reads made before the map has projected anything. */
internal val EMPTY_CORNERS: List<Position> = List(4) { Position(0.0, 0.0) }

/**
 * The map's corners as positions, ordered top-left, top-right, bottom-left, bottom-right.
 *
 * `latLngBoundsForCamera` hulls only the top-left and bottom-right corners, so it misses parts of
 * the viewport whenever the camera is rotated or pitched. Unproject all four corners instead.
 *
 * The projection freezes its transform when it is created, so this answers for that transform on
 * any thread that holds the projection open.
 */
internal fun unprojectedCorners(
  projection: MapProjectionHandle,
  size: DpSize,
): List<Position> {
  val width = size.width.value.toDouble()
  val height = size.height.value.toDouble()
  return listOf(
      ScreenPoint(0.0, 0.0),
      ScreenPoint(width, 0.0),
      ScreenPoint(0.0, height),
      ScreenPoint(width, height),
    )
    .map { projection.latLngForPixelUnwrapped(it).toPosition() }
}
