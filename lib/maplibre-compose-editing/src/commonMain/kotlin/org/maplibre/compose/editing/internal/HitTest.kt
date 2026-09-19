package org.maplibre.compose.editing.internal

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import org.maplibre.compose.editing.EditorFeature
import org.maplibre.compose.editing.EditorHandle
import org.maplibre.compose.editing.EditorHit
import org.maplibre.compose.editing.FeatureHit
import org.maplibre.compose.editing.HandleHit
import org.maplibre.compose.editing.HandleKind
import org.maplibre.compose.editing.VertexRef
import org.maplibre.spatialk.geojson.FeatureId
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/** Geographic extent of a geometry's coordinates as written, without longitude wrapping. */
internal class LonLatBounds(
  val west: Double,
  val south: Double,
  val east: Double,
  val north: Double,
) {
  val centerLongitude: Double
    get() = (west + east) / 2

  /** Whether the two boxes overlap on some copy of the world. */
  fun intersects(other: LonLatBounds): Boolean {
    if (other.south > north || other.north < south) return false
    val shift = round((centerLongitude - other.centerLongitude) / 360.0) * 360.0
    for (k in -1..1) {
      val offset = shift + k * 360.0
      if (other.west + offset <= east && other.east + offset >= west) return true
    }
    return false
  }

  companion object {
    fun of(geometry: Geometry): LonLatBounds {
      var west = Double.POSITIVE_INFINITY
      var south = Double.POSITIVE_INFINITY
      var east = Double.NEGATIVE_INFINITY
      var north = Double.NEGATIVE_INFINITY
      geometry.forEachVertex { _, p ->
        west = min(west, p.longitude)
        east = max(east, p.longitude)
        south = min(south, p.latitude)
        north = max(north, p.latitude)
      }
      return LonLatBounds(west, south, east, north)
    }
  }
}

/** Bounds per feature, reused while the feature instance stays the same. */
internal class FeatureBoundsCache {
  private val entries = HashMap<FeatureId, Pair<EditorFeature, LonLatBounds>>()

  fun boundsOf(feature: EditorFeature): LonLatBounds {
    val id = checkNotNull(feature.id)
    entries[id]?.let { (cached, bounds) -> if (cached === feature) return bounds }
    return LonLatBounds.of(feature.geometry).also { entries[id] = feature to it }
  }

  fun retain(ids: Set<FeatureId>) {
    entries.keys.retainAll(ids)
  }
}

/**
 * Hits at [screen]: handles nearest first, then [candidates] in order. The tolerance is [radius]
 * unprojected at [screen] as the largest Mercator distance to a corner of the radius square.
 */
internal fun computeHits(
  handles: List<EditorHandle>,
  candidates: List<EditorFeature>,
  screen: DpOffset,
  radius: Dp,
  unproject: (DpOffset) -> Position?,
  fill: Boolean,
  bounds: FeatureBoundsCache,
): List<EditorHit> {
  val rawCenter = unproject(screen) ?: return emptyList()
  val center = rawCenter.withLonLat(wrapLongitude(rawCenter.longitude), rawCenter.latitude)
  val corners =
    listOf(
        DpOffset(screen.x - radius, screen.y - radius),
        DpOffset(screen.x + radius, screen.y - radius),
        DpOffset(screen.x - radius, screen.y + radius),
        DpOffset(screen.x + radius, screen.y + radius),
      )
      .mapNotNull(unproject)
  if (corners.isEmpty()) return emptyList()
  val tolerance = corners.maxOf { mercatorDistance(center, it) }
  val px = mercatorX(center.longitude)
  val py = mercatorY(center.latitude)
  // Raw longitudes are contiguous around the pointer; the pad absorbs rounding at the edge.
  val searchBounds =
    corners.plusElement(rawCenter).let { all ->
      LonLatBounds(
        west = all.minOf { it.longitude } - BOUNDS_PAD,
        south = all.minOf { it.latitude } - BOUNDS_PAD,
        east = all.maxOf { it.longitude } + BOUNDS_PAD,
        north = all.maxOf { it.latitude } + BOUNDS_PAD,
      )
    }
  fun toDp(distance: Double): Dp =
    if (tolerance == 0.0) 0.dp else (distance / tolerance * radius.value).toFloat().dp

  val hits = ArrayList<EditorHit>()
  handles
    .mapNotNull { handle ->
      val distance = mercatorDistance(center, handle.position)
      if (distance <= tolerance) handle to distance else null
    }
    .sortedWith(
      compareBy<Pair<EditorHandle, Double>> { it.second }
        .thenBy { if (it.first.vertex?.featureId == null) 0 else 1 }
        .thenBy { it.first.kind.rank() }
    )
    .mapTo(hits) { HandleHit(it.first) }

  for (feature in candidates) {
    val featureBounds = bounds.boundsOf(feature)
    if (!featureBounds.intersects(searchBounds)) continue
    val nearest = nearestSegment(feature.geometry, px, py)
    val insideFill =
      fill && containsPoint(feature.geometry, center.longitude, center.latitude, featureBounds)
    if (nearest == null) {
      if (insideFill) hits += FeatureHit(checkNotNull(feature.id), null, 0.dp)
      continue
    }
    if (nearest.distance <= tolerance || insideFill) {
      hits +=
        FeatureHit(
          featureId = checkNotNull(feature.id),
          segmentStart = nearest.path?.let { VertexRef(feature.id, it) },
          distance = toDp(nearest.distance),
        )
    }
  }
  return hits
}

private const val BOUNDS_PAD = 1e-9

private fun HandleKind.rank(): Int =
  when (this) {
    HandleKind.Vertex -> 0
    HandleKind.Midpoint -> 1
    else -> 2
  }

private class Nearest(val path: List<Int>?, val distance: Double)

/**
 * The nearest segment start path (or position path for points) and its world-unit distance. Null
 * for an empty geometry.
 */
private fun nearestSegment(geometry: Geometry, px: Double, py: Double): Nearest? {
  var bestPath: List<Int>? = null
  var best = Double.POSITIVE_INFINITY
  var found = false
  when (geometry) {
    is Point,
    is MultiPoint ->
      geometry.forEachVertex { path, p ->
        val distance = mercatorPointDistance(px, py, p)
        if (distance < best) {
          best = distance
          bestPath = path
        }
        found = true
      }
    is LineString,
    is MultiLineString,
    is Polygon,
    is MultiPolygon ->
      geometry.forEachSegment { path, a, b ->
        val distance = mercatorSegmentDistance(px, py, a, b)
        if (distance < best) {
          best = distance
          bestPath = path
        }
        found = true
      }
    is GeometryCollection<*> ->
      geometry.geometries.forEach { part ->
        val nearest = nearestSegment(part, px, py) ?: return@forEach
        if (nearest.distance < best) best = nearest.distance
        found = true
      }
  }
  return if (found) Nearest(if (geometry is GeometryCollection<*>) null else bestPath, best)
  else null
}

private fun mercatorPointDistance(px: Double, py: Double, p: Position): Double {
  val dx = wrapMercatorDx(mercatorX(p.longitude) - px)
  val dy = mercatorY(p.latitude) - py
  return kotlin.math.sqrt(dx * dx + dy * dy)
}

/** Even-odd ray cast in geographic space over polygon parts, with holes excluded. */
private fun containsPoint(
  geometry: Geometry,
  longitude: Double,
  latitude: Double,
  bounds: LonLatBounds,
): Boolean {
  val lon = longitude + round((bounds.centerLongitude - longitude) / 360.0) * 360.0
  return when (geometry) {
    is Polygon -> ringsContain(geometry.coordinates, lon, latitude)
    is MultiPolygon -> geometry.coordinates.any { ringsContain(it, lon, latitude) }
    is GeometryCollection<*> ->
      geometry.geometries.any { containsPoint(it, longitude, latitude, LonLatBounds.of(it)) }
    else -> false
  }
}

private fun ringsContain(rings: List<List<Position>>, lon: Double, lat: Double): Boolean {
  if (rings.isEmpty() || !ringContains(rings[0], lon, lat)) return false
  for (i in 1 until rings.size) if (ringContains(rings[i], lon, lat)) return false
  return true
}

private fun ringContains(ring: List<Position>, lon: Double, lat: Double): Boolean {
  var inside = false
  var j = ring.size - 1
  for (i in ring.indices) {
    val a = ring[i]
    val b = ring[j]
    if (
      (a.latitude > lat) != (b.latitude > lat) &&
        lon <
          (b.longitude - a.longitude) * (lat - a.latitude) / (b.latitude - a.latitude) + a.longitude
    ) {
      inside = !inside
    }
    j = i
  }
  return inside
}
