package org.maplibre.compose.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import org.maplibre.compose.gljs.PaddingOptions
import org.maplibre.spatialk.geojson.Position

/** A fitted center and zoom; bearing and tilt come from the request. */
internal class GeometryFit(val target: Position, val zoom: Double)

/**
 * Fits [positions] the way GL JS `cameraForBoxAndBearing` fits the corners of a box: project to
 * Mercator, rotate by [bearing], and scale the rotated extent into the viewport left after
 * [edgePadding] and [fitPadding]. Pitch is not part of the calculation. Returns null when the
 * padding leaves no room.
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

private const val TILE_SIZE = 512.0

private data class WorldPoint(val x: Double, val y: Double) {
  fun rotate(radians: Double): WorldPoint {
    val c = cos(radians)
    val s = sin(radians)
    return WorldPoint(x = c * x - s * y, y = s * x + c * y)
  }

  fun scale(factor: Double) = WorldPoint(x * factor, y * factor)

  operator fun minus(other: WorldPoint) = WorldPoint(x - other.x, y - other.y)
}

private fun project(position: Position, worldSize: Double): WorldPoint {
  val x = (180.0 + position.longitude) / 360.0
  val y = (180.0 - (180.0 / PI) * ln(tan(PI / 4.0 + position.latitude * PI / 360.0))) / 360.0
  return WorldPoint(x * worldSize, y * worldSize)
}

private fun unproject(point: WorldPoint, worldSize: Double): Position {
  val longitude = point.x / worldSize * 360.0 - 180.0
  val y2 = 180.0 - point.y / worldSize * 360.0
  val latitude = 360.0 / PI * atan(exp(y2 * PI / 180.0)) - 90.0
  return Position(longitude = longitude, latitude = latitude)
}
