package org.maplibre.compose.editing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

class GeometryTest {
  @Test
  fun map_positions_keeps_structure_closure_and_foreign_members() {
    val members = buildJsonObject { put("extra", 1) }
    val polygon =
      Polygon(
        listOf(squareGeometry().coordinates[0]),
        bbox = BoundingBox(0.0, 0.0, 10.0, 10.0),
        foreignMembers = members,
      )
    val shifted = polygon.mapPositions { Position(it.longitude + 1, it.latitude) }
    assertEquals(pos(1.0, 0.0), shifted.coordinates[0].first())
    assertEquals(shifted.coordinates[0].first(), shifted.coordinates[0].last())
    assertEquals(5, shifted.coordinates[0].size)
    assertNull(shifted.bbox)
    assertEquals(members, shifted.foreignMembers)
    val multi =
      MultiPolygon(listOf(listOf(squareGeometry().coordinates[0]))).mapPositions {
        pos(it.latitude, it.longitude)
      }
    assertEquals(pos(0.0, 10.0), multi.coordinates[0][0][1])
    val collection: GeometryCollection<*> =
      GeometryCollection(listOf(Point(pos(1.0, 2.0)))).mapPositions {
        pos(it.longitude * 2, it.latitude)
      }
    assertEquals(Point(pos(2.0, 2.0)), collection.geometries.single())
  }

  @Test
  fun bounding_box_contains_compares_longitudes_modulo_360() {
    val box = BoundingBox(170.0, -10.0, 190.0, 10.0)
    assertTrue(box.contains(pos(175.0, 0.0)))
    assertTrue(box.contains(pos(-175.0, 0.0)))
    assertFalse(box.contains(pos(160.0, 0.0)))
    assertFalse(box.contains(pos(175.0, 20.0)))
    val crossing = BoundingBox(170.0, -10.0, -170.0, 10.0)
    assertTrue(crossing.contains(pos(180.0, 0.0)))
    assertFalse(crossing.contains(pos(0.0, 0.0)))
    assertTrue(BoundingBox(-180.0, -90.0, 180.0, 90.0).contains(pos(123.0, 45.0)))
  }
}
