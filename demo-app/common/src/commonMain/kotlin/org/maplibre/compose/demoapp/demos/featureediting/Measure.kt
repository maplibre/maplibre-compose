package org.maplibre.compose.demoapp.demos.featureediting

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.maplibre.compose.editing.EditorFeature
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.turf.booleans.contains
import org.maplibre.spatialk.turf.featureconversion.toMultiLineString
import org.maplibre.spatialk.turf.measurement.area
import org.maplibre.spatialk.turf.measurement.bearingTo
import org.maplibre.spatialk.turf.measurement.center
import org.maplibre.spatialk.turf.measurement.computeBbox
import org.maplibre.spatialk.turf.measurement.distance
import org.maplibre.spatialk.turf.measurement.length
import org.maplibre.spatialk.turf.measurement.locateAlong
import org.maplibre.spatialk.turf.measurement.midpoint
import org.maplibre.spatialk.turf.misc.intersect
import org.maplibre.spatialk.turf.misc.nearestPointTo
import org.maplibre.spatialk.turf.transformation.simplify
import org.maplibre.spatialk.units.Area
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.DMS.Degrees
import org.maplibre.spatialk.units.Imperial
import org.maplibre.spatialk.units.International
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.Metric
import org.maplibre.spatialk.units.extensions.meters
import org.maplibre.spatialk.units.extensions.squareMeters

/** One edge of a shape with its length and label position. */
internal class Edge(val a: Position, val b: Position, val length: Length, val midpoint: Position)

/** Turf measurements of one shape. */
internal class ShapeMeasure(
  val kind: ShapeKind,
  val area: Area,
  /** Perimeter, line length, or circumference. */
  val length: Length,
  val straight: Length?,
  val bearing: Bearing?,
  val extentWidth: Length,
  val extentHeight: Length,
  val center: Position,
  val radius: Length?,
  /** Distinct vertices. */
  val corners: Int,
  val edges: List<Edge>,
  /**
   * Where a polygon or circle label sits: the bbox centre when inside, else the nearest outline
   * point.
   */
  val labelAnchor: Position,
) {
  /** The primary number: area for polygons and circles, length for lines. */
  val primaryMeters: Double
    get() =
      if (kind == ShapeKind.Line) length.toDouble(International.Meters)
      else area.toDouble(International.SquareMeters)

  companion object {
    fun of(feature: EditorFeature): ShapeMeasure {
      val geometry = feature.geometry
      val kind = feature.kind
      val bbox = geometry.computeBbox()
      val center = bbox.center().coordinates
      val extentWidth =
        distance(Position(bbox.west, center.latitude), Position(bbox.east, center.latitude))
      val extentHeight =
        distance(Position(center.longitude, bbox.south), Position(center.longitude, bbox.north))
      val lines = outlineLines(geometry)
      val edges = lines.flatMap { line ->
        line.zipWithNext { a, b -> Edge(a, b, distance(a, b), midpoint(a, b)) }
      }
      val distinct = distinctPositions(geometry)
      val first = (geometry as? LineString)?.coordinates?.first()
      val last = (geometry as? LineString)?.coordinates?.last()
      val labelAnchor =
        when (geometry) {
          is Polygon ->
            if (geometry.contains(center)) center
            else geometry.toMultiLineString().nearestPointTo(center).geometry.coordinates
          else -> center
        }
      return ShapeMeasure(
        kind = kind,
        area = geometry.area(),
        length = geometry.length(),
        straight = if (first != null && last != null) distance(first, last) else null,
        bearing = if (first != null && last != null) first.bearingTo(last) else null,
        extentWidth = extentWidth,
        extentHeight = extentHeight,
        center = center,
        radius = if (kind == ShapeKind.Circle) circleRadius(geometry, center) else null,
        corners = distinct,
        edges = edges,
        labelAnchor = labelAnchor,
      )
    }
  }
}

/** The point [fraction] of the way along [line]. */
internal fun stationPoint(line: LineString, fraction: Double): Position =
  line.locateAlong(line.length() * fraction.coerceIn(0.0, 1.0)).coordinates

/** A point along a line with its distance from the start and the bearing of its segment. */
internal class Station(val position: Position, val along: Length, val bearing: Bearing)

internal fun stationOf(line: LineString, fraction: Double): Station {
  val along = line.length() * fraction.coerceIn(0.0, 1.0)
  val coordinates = line.coordinates
  var travelled = Length.Zero
  var index = 0
  while (index < coordinates.size - 2) {
    val segment = distance(coordinates[index], coordinates[index + 1])
    if (travelled + segment >= along) break
    travelled += segment
    index++
  }
  return Station(
    position = line.locateAlong(along).coordinates,
    along = along,
    bearing = coordinates[index].bearingTo(coordinates[index + 1]),
  )
}

/** The centre of a circle feature: its bounding box centre. */
internal fun circleCenter(geometry: Geometry): Position =
  geometry.computeBbox().center().coordinates

/** The radius of a circle feature: the distance from its centre to its first vertex. */
internal fun circleRadius(geometry: Geometry, center: Position = circleCenter(geometry)): Length {
  val first =
    (geometry as? Polygon)?.coordinates?.firstOrNull()?.firstOrNull() ?: return Length.Zero
  return distance(center, first)
}

/** The rings and lines whose segments make up the outline of [geometry]. */
private fun outlineLines(geometry: Geometry): List<List<Position>> =
  when (geometry) {
    is LineString -> listOf(geometry.coordinates)
    is MultiLineString -> geometry.coordinates
    is Polygon -> geometry.coordinates
    is MultiPolygon -> geometry.coordinates.flatten()
    else -> emptyList()
  }

/** The number of distinct vertices, skipping ring closures. */
internal fun distinctPositions(geometry: Geometry): Int =
  when (geometry) {
    is Polygon -> geometry.coordinates.sumOf { it.size - 1 }
    is MultiPolygon -> geometry.coordinates.sumOf { rings -> rings.sumOf { it.size - 1 } }
    is LineString -> geometry.coordinates.size
    is MultiLineString -> geometry.coordinates.sumOf { it.size }
    else -> 0
  }

/** The smallest box around every geometry, or null for an empty list. */
internal fun List<Geometry>.unionBbox(): BoundingBox? {
  if (isEmpty()) return null
  var west = Double.POSITIVE_INFINITY
  var south = Double.POSITIVE_INFINITY
  var east = Double.NEGATIVE_INFINITY
  var north = Double.NEGATIVE_INFINITY
  for (geometry in this) {
    val bbox = geometry.computeBbox()
    west = minOf(west, bbox.west)
    south = minOf(south, bbox.south)
    east = maxOf(east, bbox.east)
    north = maxOf(north, bbox.north)
  }
  return BoundingBox(west, south, east, north)
}

internal const val SELF_CROSSING_MESSAGE = "Outline would cross itself"

// A ring above this size is not checked, so a long drag on the browser stays at frame rate.
private const val VALIDATED_SEGMENT_LIMIT = 120

/** Rejects a polygon whose outline crosses itself. Circles and lines are accepted. */
internal fun validateShape(feature: EditorFeature): String? {
  if (feature.isCircle) return null
  val polygon = feature.geometry as? Polygon ?: return null
  for (ring in polygon.coordinates) {
    if (ringCrossesItself(ring)) return SELF_CROSSING_MESSAGE
  }
  return null
}

private fun ringCrossesItself(ring: List<Position>): Boolean {
  val points = if (ring.size > 1 && ring.first() == ring.last()) ring.dropLast(1) else ring
  val n = points.size
  if (n < 4 || n > VALIDATED_SEGMENT_LIMIT) return false
  val minLon = DoubleArray(n)
  val maxLon = DoubleArray(n)
  val minLat = DoubleArray(n)
  val maxLat = DoubleArray(n)
  for (i in 0 until n) {
    val a = points[i]
    val b = points[(i + 1) % n]
    minLon[i] = minOf(a.longitude, b.longitude)
    maxLon[i] = maxOf(a.longitude, b.longitude)
    minLat[i] = minOf(a.latitude, b.latitude)
    maxLat[i] = maxOf(a.latitude, b.latitude)
  }
  for (i in 0 until n) {
    for (j in i + 2 until n) {
      if (i == 0 && j == n - 1) continue
      if (
        minLon[i] > maxLon[j] ||
          minLon[j] > maxLon[i] ||
          minLat[i] > maxLat[j] ||
          minLat[j] > maxLat[i]
      )
        continue
      val first = LineString(points[i], points[(i + 1) % n])
      val second = LineString(points[j], points[(j + 1) % n])
      if (intersect(first, second) != null) return true
    }
  }
  return false
}

/**
 * Simplifies [feature] with Douglas-Peucker at [toleranceDegrees]. A polygon ring that would keep
 * fewer than four positions takes the matching ring of [fallback] instead.
 */
internal fun simplified(
  feature: EditorFeature,
  toleranceDegrees: Double,
  fallback: EditorFeature = feature,
): EditorFeature {
  val geometry: Geometry =
    when (val current = feature.geometry) {
      is LineString -> current.simplify(toleranceDegrees, highestQuality = true)
      is Polygon -> {
        val fallbackRings = (fallback.geometry as? Polygon)?.coordinates
        Polygon(
          current.coordinates.mapIndexed { index, ring ->
            var simplified =
              LineString(ring).simplify(toleranceDegrees, highestQuality = true).coordinates
            if (simplified.first() != simplified.last())
              simplified = simplified.plusElement(simplified.first())
            if (simplified.size < 4) fallbackRings?.getOrNull(index) ?: ring else simplified
          }
        )
      }
      else -> return feature
    }
  return feature.copy(geometry = geometry, bbox = null)
}

/** The simplify tolerance for slider value [value] over [geometry], in degrees. */
internal fun simplifyTolerance(geometry: Geometry, value: Float): Double {
  val bbox = geometry.computeBbox()
  val extent = maxOf(bbox.east - bbox.west, bbox.north - bbox.south)
  return value.toDouble().pow(2) * 0.12 * extent
}

/** [degrees] of longitude at [latitude], as a length. */
internal fun degreesToMeters(degrees: Double, latitude: Double): Length =
  (degrees * METERS_PER_DEGREE * cos(latitude * PI / 180)).meters

internal const val METERS_PER_DEGREE = 111_320.0

/** Formats [value] with thousands separators and [decimals] fraction digits. */
internal fun formatNumber(value: Double, decimals: Int): String {
  val factor = 10.0.pow(decimals)
  val scaled = (abs(value) * factor).roundToLong()
  val whole = (scaled / factor.toLong()).toString()
  val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
  val text =
    if (decimals == 0) grouped
    else "$grouped.${(scaled % factor.toLong()).toString().padStart(decimals, '0')}"
  return if (value < 0 && scaled != 0L) "−$text" else text
}

internal fun MeasureUnits.length(length: Length): String =
  when (this) {
    MeasureUnits.Metric -> {
      val meters = length.toDouble(International.Meters)
      if (meters < 1_000) "${formatNumber(meters, 0)} m"
      else {
        val km = length.toDouble(International.Kilometers)
        "${formatNumber(km, if (km >= 100) 0 else 2)} km"
      }
    }
    MeasureUnits.Imperial -> {
      val miles = length.toDouble(Imperial.Miles)
      if (miles < 0.5) "${formatNumber(length.toDouble(Imperial.Feet), 0)} ft"
      else "${formatNumber(miles, if (miles >= 100) 0 else 2)} mi"
    }
  }

internal fun MeasureUnits.area(area: Area): String =
  when (this) {
    MeasureUnits.Metric -> {
      val hectares = area.toDouble(Metric.Hectares)
      when {
        hectares < 1 -> "${formatNumber(area.toDouble(International.SquareMeters), 0)} m²"
        hectares < 10_000 -> "${formatNumber(hectares, if (hectares >= 10) 0 else 2)} ha"
        else -> "${formatNumber(area.toDouble(International.SquareKilometers), 2)} km²"
      }
    }
    MeasureUnits.Imperial -> {
      val acres = area.toDouble(Imperial.Acres)
      when {
        acres < 0.5 -> "${formatNumber(area.toDouble(Imperial.SquareFeet), 0)} ft²"
        acres < 6_400 -> "${formatNumber(acres, if (acres >= 10) 0 else 2)} ac"
        else -> "${formatNumber(area.toDouble(Imperial.SquareMiles), 2)} mi²"
      }
    }
  }

internal fun MeasureUnits.bearing(bearing: Bearing): String =
  "${(bearing - Bearing.North).toDouble(Degrees).roundToInt() % 360}°"

@Suppress("UnusedReceiverParameter")
internal fun MeasureUnits.coordinate(position: Position): String =
  "${formatNumber(position.latitude, 4)}, ${formatNumber(position.longitude, 4)}"

/** Formats the primary number of a [kind] shape from [meters] (square metres for areas). */
internal fun MeasureUnits.primary(kind: ShapeKind, meters: Double): String =
  if (kind == ShapeKind.Line) length(meters.meters) else area(meters.squareMeters)
