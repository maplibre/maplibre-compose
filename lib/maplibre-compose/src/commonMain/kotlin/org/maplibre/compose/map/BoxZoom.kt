package org.maplibre.compose.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

internal class BoxZoomPreview {
  private var origin: DpOffset? = null
  var bounds: DpRect? by mutableStateOf(null)
    private set

  fun start(origin: DpOffset, current: DpOffset) {
    this.origin = origin
    move(current)
  }

  fun move(current: DpOffset) {
    val start = origin ?: return
    bounds =
      DpRect(
        minOf(start.x, current.x),
        minOf(start.y, current.y),
        maxOf(start.x, current.x),
        maxOf(start.y, current.y),
      )
  }

  fun clear(): DpRect? {
    val result = bounds
    origin = null
    bounds = null
    return result
  }
}

internal fun Modifier.drawBoxZoom(preview: BoxZoomPreview): Modifier = drawWithContent {
  drawContent()
  preview.bounds?.let { rect ->
    val topLeft = Offset(rect.left.toPx(), rect.top.toPx())
    val size = Size((rect.right - rect.left).toPx(), (rect.bottom - rect.top).toPx())
    val color = Color(0xff1976d2)
    clipRect {
      drawRect(color.copy(alpha = 0.15f), topLeft, size)
      drawRect(color, topLeft, size, style = Stroke(1.dp.toPx()))
    }
  }
}

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
