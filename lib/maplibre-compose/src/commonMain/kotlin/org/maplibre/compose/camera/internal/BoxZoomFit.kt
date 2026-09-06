package org.maplibre.compose.camera.internal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

internal data class BoxZoomFit(val bounds: BoundingBox, val bearing: Double, val tilt: Double)

/** All corners must come from the same presentation snapshot. */
internal fun boxZoomFit(
  rect: DpRect,
  camera: CameraPosition,
  project: (DpOffset) -> Position?,
): BoxZoomFit? {
  if (rect.right - rect.left < 8.dp || rect.bottom - rect.top < 8.dp) return null
  if (listOf(rect.left, rect.top, rect.right, rect.bottom).any { !it.value.isFinite() }) return null
  val corners =
    listOf(
      DpOffset(rect.left, rect.top),
      DpOffset(rect.right, rect.top),
      DpOffset(rect.right, rect.bottom),
      DpOffset(rect.left, rect.bottom),
    )
  var west = Double.POSITIVE_INFINITY
  var east = Double.NEGATIVE_INFINITY
  var south = Double.POSITIVE_INFINITY
  var north = Double.NEGATIVE_INFINITY
  for (corner in corners) {
    val position = project(corner) ?: return null
    if (!position.longitude.isFinite() || !position.latitude.isFinite()) return null
    val longitude =
      position.longitude + 360.0 * round((camera.target.longitude - position.longitude) / 360.0)
    west = min(west, longitude)
    east = max(east, longitude)
    south = min(south, position.latitude)
    north = max(north, position.latitude)
  }
  return BoxZoomFit(
    BoundingBox(Position(west, south), Position(east, north)),
    camera.bearing,
    camera.tilt,
  )
}
