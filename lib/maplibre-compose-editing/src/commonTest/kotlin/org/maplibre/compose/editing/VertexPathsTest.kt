package org.maplibre.compose.editing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon

class VertexPathsTest {
  private val moved = pos(9.0, 9.0)

  private fun ring(origin: Double) = squareGeometry(origin).coordinates[0]

  @Test
  fun point_path_is_empty() {
    val state = FeatureEditorState(listOf(point("p")))
    assertTrue(state.moveVertex(VertexRef(id("p"), emptyList()), moved))
    assertEquals(moved, (state.feature(id("p"))!!.geometry as Point).coordinates)
    assertFalse(state.moveVertex(VertexRef(id("p"), listOf(0)), moved))
    assertFalse(state.insertVertex(VertexRef(id("p"), emptyList()), moved))
    assertFalse(state.removeVertex(VertexRef(id("p"), emptyList())))
  }

  @Test
  fun multi_point_paths_index_positions() {
    val state =
      FeatureEditorState(listOf(feature(MultiPoint(listOf(pos(0.0, 0.0), pos(1.0, 0.0))), "m")))
    val id = id("m")
    assertTrue(state.moveVertex(VertexRef(id, listOf(1)), moved))
    assertEquals(moved, (state.feature(id)!!.geometry as MultiPoint).coordinates[1])
    assertTrue(state.insertVertex(VertexRef(id, listOf(0)), pos(5.0, 5.0)))
    assertEquals(pos(5.0, 5.0), (state.feature(id)!!.geometry as MultiPoint).coordinates[0])
    assertTrue(state.removeVertex(VertexRef(id, listOf(2))))
    assertTrue(state.removeVertex(VertexRef(id, listOf(0))))
    assertFalse(state.removeVertex(VertexRef(id, listOf(0))))
    assertEquals(1, (state.feature(id)!!.geometry as MultiPoint).coordinates.size)
    assertFalse(state.moveVertex(VertexRef(id, listOf(4)), moved))
  }

  @Test
  fun line_string_paths_index_positions() {
    val state = FeatureEditorState(listOf(line("l", pos(0.0, 0.0), pos(1.0, 0.0), pos(2.0, 0.0))))
    val id = id("l")
    assertTrue(state.moveVertex(VertexRef(id, listOf(2)), moved))
    assertEquals(moved, (state.feature(id)!!.geometry as LineString).coordinates[2])
    assertTrue(state.insertVertex(VertexRef(id, listOf(3)), pos(3.0, 0.0)))
    assertEquals(4, (state.feature(id)!!.geometry as LineString).coordinates.size)
    assertFalse(state.insertVertex(VertexRef(id, listOf(0, 0)), pos(3.0, 0.0)))
    assertTrue(state.removeVertex(VertexRef(id, listOf(1))))
    assertEquals(
      listOf(pos(0.0, 0.0), moved, pos(3.0, 0.0)),
      (state.feature(id)!!.geometry as LineString).coordinates,
    )
  }

  @Test
  fun multi_line_string_paths_index_part_then_position() {
    val geometry =
      MultiLineString(
        listOf(
          listOf(pos(0.0, 0.0), pos(1.0, 0.0)),
          listOf(pos(0.0, 5.0), pos(1.0, 5.0), pos(2.0, 5.0)),
        )
      )
    val state = FeatureEditorState(listOf(feature(geometry, "m")))
    val id = id("m")
    assertTrue(state.moveVertex(VertexRef(id, listOf(1, 2)), moved))
    assertEquals(moved, (state.feature(id)!!.geometry as MultiLineString).coordinates[1][2])
    assertTrue(state.insertVertex(VertexRef(id, listOf(0, 2)), pos(2.0, 0.0)))
    assertEquals(3, (state.feature(id)!!.geometry as MultiLineString).coordinates[0].size)
    assertTrue(state.removeVertex(VertexRef(id, listOf(0, 0))))
    assertFalse(state.removeVertex(VertexRef(id, listOf(0, 0))))
    assertFalse(state.moveVertex(VertexRef(id, listOf(2, 0)), moved))
  }

  @Test
  fun polygon_paths_skip_the_closing_position() {
    val state = FeatureEditorState(listOf(feature(Polygon(listOf(ring(0.0), ring(2.0))), "p")))
    val id = id("p")
    assertFalse(state.moveVertex(VertexRef(id, listOf(0, 4)), moved))
    assertTrue(state.moveVertex(VertexRef(id, listOf(1, 0)), moved))
    val hole = (state.feature(id)!!.geometry as Polygon).coordinates[1]
    assertEquals(moved, hole[0])
    assertEquals(moved, hole[4])
    assertTrue(state.insertVertex(VertexRef(id, listOf(0, 4)), pos(-1.0, 5.0)))
    val outer = (state.feature(id)!!.geometry as Polygon).coordinates[0]
    assertEquals(6, outer.size)
    assertEquals(pos(-1.0, 5.0), outer[4])
    assertEquals(outer.first(), outer.last())
    assertTrue(state.removeVertex(VertexRef(id, listOf(0, 0))))
    val afterRemove = (state.feature(id)!!.geometry as Polygon).coordinates[0]
    assertEquals(5, afterRemove.size)
    assertEquals(afterRemove.first(), afterRemove.last())
    assertEquals(pos(10.0, 0.0), afterRemove.first())
  }

  @Test
  fun multi_polygon_paths_index_part_ring_position() {
    val geometry = MultiPolygon(listOf(listOf(ring(0.0)), listOf(ring(20.0), ring(22.0))))
    val state = FeatureEditorState(listOf(feature(geometry, "m")))
    val id = id("m")
    assertTrue(state.moveVertex(VertexRef(id, listOf(1, 1, 3)), moved))
    val hole = (state.feature(id)!!.geometry as MultiPolygon).coordinates[1][1]
    assertEquals(moved, hole[3])
    assertEquals(hole.first(), hole.last())
    assertTrue(state.insertVertex(VertexRef(id, listOf(0, 0, 0)), pos(-5.0, -5.0)))
    val outer = (state.feature(id)!!.geometry as MultiPolygon).coordinates[0][0]
    assertEquals(pos(-5.0, -5.0), outer.first())
    assertEquals(pos(-5.0, -5.0), outer.last())
    assertTrue(state.removeVertex(VertexRef(id, listOf(1, 0, 0))))
    assertFalse(state.removeVertex(VertexRef(id, listOf(1, 0, 0))))
    assertFalse(state.moveVertex(VertexRef(id, listOf(1, 0)), moved))
  }

  @Test
  fun geometry_collection_has_no_paths() {
    val state =
      FeatureEditorState(listOf(feature(GeometryCollection(listOf(Point(pos(0.0, 0.0)))), "g")))
    assertFalse(state.moveVertex(VertexRef(id("g"), emptyList()), moved))
    assertFalse(state.moveVertex(VertexRef(id("g"), listOf(0)), moved))
    assertFalse(state.removeVertex(VertexRef(id("g"), listOf(0))))
  }
}
