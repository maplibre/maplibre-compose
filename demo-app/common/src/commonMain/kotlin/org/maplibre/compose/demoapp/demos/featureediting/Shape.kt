package org.maplibre.compose.demoapp.demos.featureediting

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.turf.measurement.bearingTo
import org.maplibre.spatialk.turf.measurement.center
import org.maplibre.spatialk.turf.measurement.computeBbox
import org.maplibre.spatialk.turf.measurement.distance
import org.maplibre.spatialk.turf.measurement.offset
import org.maplibre.spatialk.turf.transformation.simplify
import org.maplibre.spatialk.units.extensions.degrees

internal enum class ShapeKind(val label: String, val minimum: Int) {
  Line("Line", 2),
  Polygon("Polygon", 3),
}

/** This example edits a line or a single polygon ring. Vertices exclude the ring closure. */
internal data class Shape(val kind: ShapeKind, val vertices: List<Position>) {
  val geometry: Geometry?
    get() =
      when {
        vertices.size < kind.minimum -> null
        kind == ShapeKind.Line -> LineString(vertices)
        else -> Polygon(listOf(vertices.plusElement(vertices.first())))
      }

  fun move(index: Int, position: Position): Shape =
    copy(vertices = vertices.toMutableList().also { it[index] = position })

  fun simplified(tolerance: Double): Shape {
    val line =
      LineString(
        if (kind == ShapeKind.Polygon) vertices.plusElement(vertices.first()) else vertices
      )
    val result = line.simplify(tolerance, highestQuality = true).coordinates
    return copy(vertices = if (kind == ShapeKind.Polygon) result.dropLast(1) else result)
  }

  fun rotated(): Shape {
    val center = checkNotNull(geometry).computeBbox().center().coordinates
    return copy(
      vertices =
        vertices.map { center.offset(distance(center, it), center.bearingTo(it) + 15.degrees) }
    )
  }

  companion object {
    fun of(geometry: Geometry): Shape =
      when (geometry) {
        is LineString -> Shape(ShapeKind.Line, geometry.coordinates)
        is Polygon ->
          Shape(
            ShapeKind.Polygon,
            geometry.coordinates.single().let { ring -> ring.dropLastWhile { it == ring.first() } },
          )
        else -> error("This demo edits lines and single-ring polygons")
      }
  }
}

/** Checks the whole ring, including large rings, in the Mercator plane used to draw its edges. */
internal fun Shape.problem(): String? {
  if (vertices.size < kind.minimum) return "Place at least ${kind.minimum} vertices."
  if (vertices.distinct().size != vertices.size) return "Vertices must be distinct."
  if (kind == ShapeKind.Line) return null
  val points = vertices.map { position ->
    val sine = sin(position.latitude.coerceIn(-85.051129, 85.051129) * PI / 180)
    PlanePoint(position.longitude / 360, -ln((1 + sine) / (1 - sine)) / (4 * PI))
  }
  for (i in points.indices) {
    val a = points[i]
    val b = points[(i + 1) % points.size]
    for (j in i + 2 until points.size) {
      if (i == 0 && j == points.lastIndex) continue
      val c = points[j]
      val d = points[(j + 1) % points.size]
      if (segmentsIntersect(a, b, c, d)) return "The outline crosses or touches itself."
    }
  }
  val twiceArea =
    points.indices.sumOf { i ->
      val a = points[i]
      val b = points[(i + 1) % points.size]
      a.x * b.y - b.x * a.y
    }
  return if (kotlin.math.abs(twiceArea) < 1e-14) "The polygon needs some area." else null
}

private data class PlanePoint(val x: Double, val y: Double)

private fun segmentsIntersect(a: PlanePoint, b: PlanePoint, c: PlanePoint, d: PlanePoint): Boolean {
  fun side(p: PlanePoint, q: PlanePoint, r: PlanePoint): Double =
    (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
  if (
    maxOf(a.x, b.x) < minOf(c.x, d.x) ||
      maxOf(c.x, d.x) < minOf(a.x, b.x) ||
      maxOf(a.y, b.y) < minOf(c.y, d.y) ||
      maxOf(c.y, d.y) < minOf(a.y, b.y)
  )
    return false
  return side(a, b, c) * side(a, b, d) <= 0 && side(c, d, a) * side(c, d, b) <= 0
}
