package org.maplibre.compose.editing.internal

import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

// Vertex paths index distinct positions. A ring's closing position is kept equal to its first
// position and is never addressable.

/** The distinct positions of a ring: every position but the closing one. */
internal fun List<Position>.ringVertices(): List<Position> =
  if (size >= 2 && first() == last()) subList(0, size - 1) else this

private fun List<Position>.closedRing(): List<Position> = plusElement(first())

/** Returns the position at [path], or null when [path] does not address one. */
internal fun Geometry.positionAt(path: List<Int>): Position? =
  when (this) {
    is Point -> if (path.isEmpty()) coordinates else null
    is MultiPoint -> if (path.size == 1) coordinates.getOrNull(path[0]) else null
    is LineString -> if (path.size == 1) coordinates.getOrNull(path[0]) else null
    is MultiLineString ->
      if (path.size == 2) coordinates.getOrNull(path[0])?.getOrNull(path[1]) else null
    is Polygon ->
      if (path.size == 2) coordinates.getOrNull(path[0])?.ringVertices()?.getOrNull(path[1])
      else null
    is MultiPolygon ->
      if (path.size == 3)
        coordinates.getOrNull(path[0])?.getOrNull(path[1])?.ringVertices()?.getOrNull(path[2])
      else null
    is GeometryCollection<*> -> null
  }

/** Returns this geometry with the vertex at [path] replaced, or null when [path] is invalid. */
internal fun Geometry.withVertexMoved(path: List<Int>, position: Position): Geometry? =
  when (this) {
    is Point -> if (path.isEmpty()) Point(position, foreignMembers = foreignMembers) else null
    is MultiPoint ->
      coordinates
        .replacedAt(path, 0) { it.set(path[0], position) }
        ?.let {
          MultiPoint(it, foreignMembers = foreignMembers)
        }
    is LineString ->
      coordinates
        .replacedAt(path, 0) { it.set(path[0], position) }
        ?.let {
          LineString(it, foreignMembers = foreignMembers)
        }
    is MultiLineString ->
      editLine(path, 1) { it.set(path[1], position) }
        ?.let {
          MultiLineString(it, foreignMembers = foreignMembers)
        }
    is Polygon ->
      editRing(path, 1) { it.set(path[1], position) }
        ?.let {
          Polygon(it, foreignMembers = foreignMembers)
        }
    is MultiPolygon ->
      editPolygonRing(path) { it.set(path[2], position) }
        ?.let {
          MultiPolygon(it, foreignMembers = foreignMembers)
        }
    is GeometryCollection<*> -> null
  }

/**
 * Returns this geometry with [position] inserted before the vertex at [path], or null when [path]
 * is invalid. An index equal to the line or ring length appends.
 */
internal fun Geometry.withVertexInserted(path: List<Int>, position: Position): Geometry? =
  when (this) {
    is Point -> null
    is MultiPoint ->
      coordinates
        .replacedAt(path, 0, allowEnd = true) { it.add(path[0], position) }
        ?.let {
          MultiPoint(it, foreignMembers = foreignMembers)
        }
    is LineString ->
      coordinates
        .replacedAt(path, 0, allowEnd = true) { it.add(path[0], position) }
        ?.let {
          LineString(it, foreignMembers = foreignMembers)
        }
    is MultiLineString ->
      editLine(path, 1, allowEnd = true) { it.add(path[1], position) }
        ?.let {
          MultiLineString(it, foreignMembers = foreignMembers)
        }
    is Polygon ->
      editRing(path, 1, allowEnd = true) { it.add(path[1], position) }
        ?.let {
          Polygon(it, foreignMembers = foreignMembers)
        }
    is MultiPolygon ->
      editPolygonRing(path, allowEnd = true) { it.add(path[2], position) }
        ?.let {
          MultiPolygon(it, foreignMembers = foreignMembers)
        }
    is GeometryCollection<*> -> null
  }

/**
 * Returns this geometry without the vertex at [path], or null when [path] is invalid, a line would
 * keep fewer than 2 positions, a ring fewer than 3 distinct positions, or a Point or MultiPoint
 * would lose its last position.
 */
internal fun Geometry.withVertexRemoved(path: List<Int>): Geometry? =
  when (this) {
    is Point -> null
    is MultiPoint ->
      if (coordinates.size < 2) null
      else
        coordinates
          .replacedAt(path, 0) { it.removeAt(path[0]) }
          ?.let {
            MultiPoint(it, foreignMembers = foreignMembers)
          }
    is LineString ->
      if (coordinates.size < 3) null
      else
        coordinates
          .replacedAt(path, 0) { it.removeAt(path[0]) }
          ?.let {
            LineString(it, foreignMembers = foreignMembers)
          }
    is MultiLineString ->
      if ((coordinates.getOrNull(path.getOrNull(0) ?: -1)?.size ?: 0) < 3) null
      else
        editLine(path, 1) { it.removeAt(path[1]) }
          ?.let {
            MultiLineString(it, foreignMembers = foreignMembers)
          }
    is Polygon ->
      if ((coordinates.getOrNull(path.getOrNull(0) ?: -1)?.ringVertices()?.size ?: 0) < 4) null
      else
        editRing(path, 1) { it.removeAt(path[1]) }
          ?.let { Polygon(it, foreignMembers = foreignMembers) }
    is MultiPolygon ->
      if (
        (coordinates
          .getOrNull(path.getOrNull(0) ?: -1)
          ?.getOrNull(path.getOrNull(1) ?: -1)
          ?.ringVertices()
          ?.size ?: 0) < 4
      )
        null
      else
        editPolygonRing(path) { it.removeAt(path[2]) }
          ?.let {
            MultiPolygon(it, foreignMembers = foreignMembers)
          }
    is GeometryCollection<*> -> null
  }

/** Calls [action] for every addressable vertex with its path. */
internal fun Geometry.forEachVertex(action: (path: List<Int>, position: Position) -> Unit) {
  when (this) {
    is Point -> action(emptyList(), coordinates)
    is MultiPoint -> coordinates.forEachIndexed { i, p -> action(listOf(i), p) }
    is LineString -> coordinates.forEachIndexed { i, p -> action(listOf(i), p) }
    is MultiLineString ->
      coordinates.forEachIndexed { part, line ->
        line.forEachIndexed { i, p -> action(listOf(part, i), p) }
      }
    is Polygon ->
      coordinates.forEachIndexed { ring, positions ->
        positions.ringVertices().forEachIndexed { i, p -> action(listOf(ring, i), p) }
      }
    is MultiPolygon ->
      coordinates.forEachIndexed { part, rings ->
        rings.forEachIndexed { ring, positions ->
          positions.ringVertices().forEachIndexed { i, p -> action(listOf(part, ring, i), p) }
        }
      }
    is GeometryCollection<*> -> geometries.forEach { it.forEachVertex(action) }
  }
}

/**
 * Calls [action] for every segment with the path of its start vertex, including the closing segment
 * of each ring. Points have no segments.
 */
internal fun Geometry.forEachSegment(
  action: (startPath: List<Int>, start: Position, end: Position) -> Unit
) {
  fun line(prefix: List<Int>, positions: List<Position>) {
    for (i in 0 until positions.size - 1) action(prefix + i, positions[i], positions[i + 1])
  }
  fun ring(prefix: List<Int>, positions: List<Position>) {
    val vertices = positions.ringVertices()
    for (i in vertices.indices) action(prefix + i, vertices[i], vertices[(i + 1) % vertices.size])
  }
  when (this) {
    is Point,
    is MultiPoint -> Unit
    is LineString -> line(emptyList(), coordinates)
    is MultiLineString -> coordinates.forEachIndexed { part, l -> line(listOf(part), l) }
    is Polygon -> coordinates.forEachIndexed { r, positions -> ring(listOf(r), positions) }
    is MultiPolygon ->
      coordinates.forEachIndexed { part, rings ->
        rings.forEachIndexed { r, positions -> ring(listOf(part, r), positions) }
      }
    is GeometryCollection<*> -> geometries.forEach { it.forEachSegment(action) }
  }
}

/** Copies this list and applies [edit] when [path] has [depth] + 1 entries and the index fits. */
private inline fun List<Position>.replacedAt(
  path: List<Int>,
  depth: Int,
  allowEnd: Boolean = false,
  edit: (MutableList<Position>) -> Unit,
): List<Position>? {
  if (path.size != depth + 1) return null
  val index = path[depth]
  if (index < 0 || index > size || (index == size && !allowEnd)) return null
  return toMutableList().also(edit)
}

private inline fun MultiLineString.editLine(
  path: List<Int>,
  depth: Int,
  allowEnd: Boolean = false,
  edit: (MutableList<Position>) -> Unit,
): List<List<Position>>? {
  if (path.size != depth + 1) return null
  val part = coordinates.getOrNull(path[0]) ?: return null
  val edited = part.replacedAt(path, depth, allowEnd, edit) ?: return null
  return coordinates.toMutableList().also { it[path[0]] = edited }
}

private inline fun Polygon.editRing(
  path: List<Int>,
  depth: Int,
  allowEnd: Boolean = false,
  edit: (MutableList<Position>) -> Unit,
): List<List<Position>>? {
  if (path.size != depth + 1) return null
  val ring = coordinates.getOrNull(path[0]) ?: return null
  val edited = ring.ringVertices().replacedAt(path, depth, allowEnd, edit) ?: return null
  return coordinates.toMutableList().also { it[path[0]] = edited.closedRing() }
}

private inline fun MultiPolygon.editPolygonRing(
  path: List<Int>,
  allowEnd: Boolean = false,
  edit: (MutableList<Position>) -> Unit,
): List<List<List<Position>>>? {
  if (path.size != 3) return null
  val rings = coordinates.getOrNull(path[0]) ?: return null
  val ring = rings.getOrNull(path[1]) ?: return null
  val edited = ring.ringVertices().replacedAt(path, 2, allowEnd, edit) ?: return null
  val newRings = rings.toMutableList().also { it[path[1]] = edited.closedRing() }
  return coordinates.toMutableList().also { it[path[0]] = newRings }
}
