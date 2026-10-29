package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.ViewportProjection
import org.maplibre.compose.util.VisibleBounds
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.toCameraPosition
import org.maplibre.compose.util.toDpOffset
import org.maplibre.compose.util.toLatLng
import org.maplibre.compose.util.toPosition
import org.maplibre.compose.util.toScreenPoint
import org.maplibre.nativeffi.EdgeInsets
import org.maplibre.nativeffi.MapProjectionHandle
import org.maplibre.nativeffi.MapSnapshot
import org.maplibre.nativeffi.MapView
import org.maplibre.nativeffi.ScreenPoint
import org.maplibre.spatialk.geojson.Position

/** The applied camera and the size it renders at, read from one map transform. */
internal data class MapViewportGeometry(
  val camera: CameraPosition,
  val padding: EdgeInsets,
  val size: DpSize,
)

/** The camera and size that one published map snapshot describes. */
internal fun MapSnapshot.viewportGeometry(viewportInsets: EdgeInsets): MapViewportGeometry =
  MapViewportGeometry(
    camera = view.camera.toCameraPosition(viewportInsets),
    padding = view.camera.padding,
    size = DpSize(view.extent.width.toInt().dp, view.extent.height.toInt().dp),
  )

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
internal val EmptyCorners: List<Position> = List(4) { Position(0.0, 0.0) }

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

/**
 * Projects for one frozen [view]. A view is plain data, so this holds no native resource and stays
 * valid after the map moves; each call opens a short-lived projection.
 */
internal class MapViewProjection(private val view: MapView) : ViewportProjection {
  override fun screenLocationFromPosition(position: Position): DpOffset =
    MapProjectionHandle.create(view).use {
      it.pixelForLatLng(position.toLatLng()).toDpOffset()
    }

  override fun positionFromScreenLocation(offset: DpOffset): Position =
    MapProjectionHandle.create(view).use {
      it.latLngForPixelUnwrapped(offset.toScreenPoint()).toPosition()
    }

  override fun metersPerDpAtLatitude(latitude: Double): Double =
    MapProjectionHandle.create(view).use {
      it.metersPerPixelAtLatitude(latitude.coerceIn(-90.0, 90.0))
    }
}
