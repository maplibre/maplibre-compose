package org.maplibre.compose.map

import js.objects.unsafeJso
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import org.maplibre.compose.gljs.GlJsTransform
import org.maplibre.compose.gljs.PaddingOptions
import org.maplibre.compose.gljs.Point
import org.maplibre.compose.util.mercatorY
import org.maplibre.compose.util.toLngLat
import org.maplibre.compose.util.toPosition
import org.maplibre.spatialk.geojson.Position

// This file vendors the GL JS bounds fit and adds pitch to it. Both become unnecessary once
// GL JS accepts destination padding (https://github.com/maplibre/maplibre-gl-js/issues/8480) and
// honors pitch (https://github.com/maplibre/maplibre-gl-js/issues/8479) in cameraForBounds.

/** A fitted center and zoom; bearing and tilt come from the request. */
internal class GeometryFit(val target: Position, val zoom: Double)

/**
 * Fits [positions] the way GL JS `cameraForBoxAndBearing` fits the corners of a box: project to
 * Mercator, rotate by [bearing], and scale the rotated extent into the viewport left after
 * [edgePadding] and [fitPadding]. Pitch is not part of the calculation; [refineFitForTilt] adds it.
 * Returns null when the padding leaves no room.
 *
 * Longitudes are used as given, so a route across the antimeridian needs continuous longitudes. The
 * result depends on [zoom] only through the pixel offset that uneven fit padding adds.
 */
internal fun fitPositions(
  positions: Sequence<Position>,
  bearing: Double,
  zoom: Double,
  width: Double,
  height: Double,
  edgePadding: PaddingOptions,
  fitPadding: PaddingOptions,
  minZoom: Double,
  maxZoom: Double,
): GeometryFit? {
  val worldSize = TILE_SIZE * 2.0.pow(zoom)
  val rotation = -bearing * PI / 180.0
  var minX = Double.POSITIVE_INFINITY
  var minY = Double.POSITIVE_INFINITY
  var maxX = Double.NEGATIVE_INFINITY
  var maxY = Double.NEGATIVE_INFINITY
  for (position in positions) {
    val (x, y) = project(position, worldSize).rotate(rotation)
    minX = min(minX, x)
    minY = min(minY, y)
    maxX = max(maxX, x)
    maxY = max(maxY, y)
  }

  val availableWidth =
    width - (edgePadding.left + edgePadding.right + fitPadding.left + fitPadding.right)
  val availableHeight =
    height - (edgePadding.top + edgePadding.bottom + fitPadding.top + fitPadding.bottom)
  if (availableWidth <= 0 || availableHeight <= 0) return null
  val scaleX = availableWidth / (maxX - minX)
  val scaleY = availableHeight / (maxY - minY)

  // A zero extent scales to infinity, which the maximum zoom absorbs.
  val fittedZoom = (zoom + log2(min(scaleX, scaleY))).coerceIn(minZoom, maxZoom)

  val paddingOffset =
    WorldPoint(
        x = (fitPadding.left - fitPadding.right) / 2.0,
        y = (fitPadding.top - fitPadding.bottom) / 2.0,
      )
      .rotate(bearing * PI / 180.0)
      .scale(2.0.pow(zoom - fittedZoom))
  val center =
    WorldPoint((minX + maxX) / 2.0, (minY + maxY) / 2.0).rotate(-rotation).minus(paddingOffset)
  return GeometryFit(target = unproject(center, worldSize), zoom = fittedZoom)
}

/**
 * Adjusts [fit] so that [positions] fill the padded viewport once the camera is tilted. Each pass
 * projects the positions through a copy of [transform] at the current candidate, then rescales the
 * screen extent into the room left by [edgePadding] and [fitPadding] and moves its midpoint to the
 * middle of that room. Perspective changes the extent as the camera moves, so the passes repeat
 * until the candidate stops changing. A tilt of zero returns [fit] unchanged.
 *
 * The projection ignores terrain. A candidate that puts a position behind the camera or past the
 * horizon ends the refinement with the last candidate that showed every position.
 */
internal fun refineFitForTilt(
  transform: GlJsTransform,
  fit: GeometryFit,
  positions: Sequence<Position>,
  bearing: Double,
  tilt: Double,
  width: Double,
  height: Double,
  edgePadding: PaddingOptions,
  fitPadding: PaddingOptions,
  minZoom: Double,
  maxZoom: Double,
): GeometryFit {
  if (tilt == 0.0) return fit
  val availableWidth =
    width - (edgePadding.left + edgePadding.right + fitPadding.left + fitPadding.right)
  val availableHeight =
    height - (edgePadding.top + edgePadding.bottom + fitPadding.top + fitPadding.bottom)
  val roomCenter =
    screenPoint(
      x = edgePadding.left + fitPadding.left + availableWidth / 2.0,
      y = edgePadding.top + fitPadding.top + availableHeight / 2.0,
    )

  val candidate = transform.clone()
  candidate.setPadding(edgePadding)
  candidate.setBearing(bearing)
  candidate.setPitch(tilt)

  // The flat fit shows every position at tilt zero; it stands in until a tilted candidate does.
  var shown = fit
  var next = fit
  repeat(MAX_TILT_PASSES) {
    candidate.setCenter(next.target.toLngLat())
    candidate.setZoom(next.zoom)
    // The map's constraints may have moved the candidate.
    val target = candidate.center.toPosition()
    val zoom = candidate.zoom

    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    for (position in positions) {
      val point = candidate.locationToScreenPoint(position.toLngLat())
      if (!candidate.isPointOnMapSurface(point)) return shown
      minX = min(minX, point.x)
      minY = min(minY, point.y)
      maxX = max(maxX, point.x)
      maxY = max(maxY, point.y)
    }
    shown = GeometryFit(target = target, zoom = zoom)
    val scale = min(availableWidth / (maxX - minX), availableHeight / (maxY - minY))
    val fittedZoom = (zoom + log2(scale)).coerceIn(minZoom, maxZoom)

    // Move the map so the location under the extent's midpoint lands in the middle of the room.
    // Mercator fractions are independent of zoom, so the shift survives the zoom change below.
    val midpoint = screenPoint(x = (minX + maxX) / 2.0, y = (minY + maxY) / 2.0)
    val shift =
      project(candidate.screenPointToLocation(midpoint).toPosition(), 1.0)
        .minus(project(candidate.screenPointToLocation(roomCenter).toPosition(), 1.0))
    val moved = unproject(project(target, 1.0).plus(shift), 1.0)

    val settled =
      abs(fittedZoom - zoom) < ZOOM_TOLERANCE &&
        abs(midpoint.x - roomCenter.x) < PIXEL_TOLERANCE &&
        abs(midpoint.y - roomCenter.y) < PIXEL_TOLERANCE
    next = GeometryFit(target = moved, zoom = fittedZoom)
    if (settled) return next
  }
  return shown
}

private const val TILE_SIZE = 512.0
private const val MAX_TILT_PASSES = 12
private const val ZOOM_TOLERANCE = 1e-4
private const val PIXEL_TOLERANCE = 0.05

private fun screenPoint(x: Double, y: Double): Point = unsafeJso {
  this.x = x
  this.y = y
}

private data class WorldPoint(val x: Double, val y: Double) {
  fun rotate(radians: Double): WorldPoint {
    val c = cos(radians)
    val s = sin(radians)
    return WorldPoint(x = c * x - s * y, y = s * x + c * y)
  }

  fun scale(factor: Double) = WorldPoint(x * factor, y * factor)

  operator fun minus(other: WorldPoint) = WorldPoint(x - other.x, y - other.y)

  operator fun plus(other: WorldPoint) = WorldPoint(x + other.x, y + other.y)
}

private fun project(position: Position, worldSize: Double): WorldPoint {
  val x = (180.0 + position.longitude) / 360.0
  val y = mercatorY(position.latitude)
  return WorldPoint(x * worldSize, y * worldSize)
}

private fun unproject(point: WorldPoint, worldSize: Double): Position {
  val longitude = point.x / worldSize * 360.0 - 180.0
  val y2 = 180.0 - point.y / worldSize * 360.0
  val latitude = 360.0 / PI * atan(exp(y2 * PI / 180.0)) - 90.0
  return Position(longitude = longitude, latitude = latitude)
}
