package org.maplibre.compose.editing

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon

class HitTestTest {
  private class HandleTool(private val handles: List<EditorHandle>) : EditorTool {
    override fun handles(state: FeatureEditorState) = handles

    override fun onEvent(event: EditorEvent, state: FeatureEditorState) = false
  }

  @Test
  fun returns_nothing_without_a_viewport() {
    val state = FeatureEditorState(listOf(square("s")))
    assertEquals(emptyList(), state.hitTest(DpOffset.Zero, 12.dp, { null }))
  }

  @Test
  fun polygon_hits_inside_with_fill_and_only_on_outline_without() {
    val state = FeatureEditorState(listOf(square("s")))
    val inside = state.hitsAt(5.0, 5.0)
    assertEquals(listOf(id("s")), inside.map { (it as FeatureHit).featureId })
    assertTrue((inside.single() as FeatureHit).distance > 2.dp)
    assertEquals(emptyList(), state.hitsAt(5.0, 5.0, fill = false))
    val edge = state.hitsAt(5.0, 0.2, fill = false).single() as FeatureHit
    assertEquals(VertexRef(id("s"), listOf(0, 0)), edge.segmentStart)
    assertTrue(edge.distance < 2.dp)
    assertEquals(emptyList(), state.hitsAt(20.0, 20.0))
  }

  @Test
  fun holes_are_excluded_from_fill_hits() {
    val hole = squareGeometry(4.0, 2.0).coordinates[0]
    val polygon = Polygon(listOf(squareGeometry().coordinates[0], hole))
    val state = FeatureEditorState(listOf(feature(polygon, "p")))
    assertEquals(emptyList(), state.hitsAt(5.0, 5.0))
    assertEquals(1, state.hitsAt(2.0, 2.0).size)
  }

  @Test
  fun segment_start_names_the_nearest_segment() {
    val state =
      FeatureEditorState(listOf(line("l", pos(0.0, 0.0), pos(10.0, 0.0), pos(10.0, 10.0))))
    val hit = state.hitsAt(10.2, 6.0).single() as FeatureHit
    assertEquals(VertexRef(id("l"), listOf(1)), hit.segmentStart)
    val closing = FeatureEditorState(listOf(square("s"))).hitsAt(0.2, 5.0).single() as FeatureHit
    assertEquals(VertexRef(id("s"), listOf(0, 3)), closing.segmentStart)
  }

  @Test
  fun points_hit_by_nearest_position() {
    val state =
      FeatureEditorState(
        listOf(feature(MultiPoint(listOf(pos(0.0, 0.0), pos(5.0, 0.0))), "m"), point("p", 5.0, 0.4))
      )
    val hits = state.hitsAt(5.0, 0.2)
    assertEquals(listOf(id("p"), id("m")), hits.map { (it as FeatureHit).featureId })
    assertEquals(VertexRef(id("m"), listOf(1)), (hits[1] as FeatureHit).segmentStart)
    assertEquals(VertexRef(id("p"), emptyList()), (hits[0] as FeatureHit).segmentStart)
    assertEquals(emptyList(), state.hitsAt(2.5, 0.0))
  }

  @Test
  fun geometry_collection_hits_without_segment_start() {
    val collection =
      GeometryCollection(
        listOf(Point(pos(1.0, 1.0)), LineString(listOf(pos(5.0, 5.0), pos(6.0, 5.0))))
      )
    val state = FeatureEditorState(listOf(feature(collection, "g")))
    val hit = state.hitsAt(5.5, 5.2).single() as FeatureHit
    assertEquals(null, hit.segmentStart)
  }

  @Test
  fun selected_features_come_first_then_top_of_list_down() {
    val state = FeatureEditorState(listOf(square("bottom"), square("middle"), square("top")))
    assertEquals(
      listOf(id("top"), id("middle"), id("bottom")),
      state.hitsAt(5.0, 5.0).map { (it as FeatureHit).featureId },
    )
    state.selection = setOf(id("bottom"))
    assertEquals(
      listOf(id("bottom"), id("top"), id("middle")),
      state.hitsAt(5.0, 5.0).map { (it as FeatureHit).featureId },
    )
  }

  @Test
  fun handles_come_first_nearest_then_draft_then_vertex_over_midpoint() {
    val near = pos(5.0, 5.0)
    val vertex = EditorHandle(HandleKind.Vertex, VertexRef(id("s"), listOf(0, 0)), near)
    val midpoint = EditorHandle(HandleKind.Midpoint, VertexRef(id("s"), listOf(0, 1)), near)
    val draft = EditorHandle(HandleKind.Vertex, VertexRef(null, listOf(0)), near)
    val nearer = EditorHandle(HandleKind.Midpoint, VertexRef(id("s"), listOf(0, 2)), pos(5.0, 5.05))
    val far = EditorHandle(HandleKind.Vertex, VertexRef(id("s"), listOf(0, 3)), pos(8.0, 8.0))
    val state =
      FeatureEditorState(
        listOf(square("s")),
        initialTool = HandleTool(listOf(midpoint, far, vertex, draft, nearer)),
      )
    val hits = state.hitsAt(5.0, 5.1)
    assertEquals(
      listOf(HandleHit(nearer), HandleHit(draft), HandleHit(vertex), HandleHit(midpoint)),
      hits.take(4),
    )
    assertIs<FeatureHit>(hits[4])
    assertEquals(5, hits.size)
  }

  @Test
  fun tolerance_follows_the_radius() {
    val state = FeatureEditorState(listOf(point("p")))
    assertEquals(1, state.hitsAt(0.15, 0.0, radius = 2.0).size)
    assertEquals(0, state.hitsAt(0.15, 0.0, radius = 1.0).size)
  }

  @Test
  fun longitudes_compare_modulo_360() {
    val state =
      FeatureEditorState(
        listOf(line("l", pos(179.0, 0.0), pos(181.0, 0.0)), point("p", 179.9, 5.0))
      )
    val hit = state.hitsAt(-179.9, 0.1).single() as FeatureHit
    assertEquals(id("l"), hit.featureId)
    assertEquals(id("p"), (state.hitsAt(-180.1, 5.0).single() as FeatureHit).featureId)
    val wrapped = FeatureEditorState(listOf(square("s", origin = 175.0, originLat = 0.0)))
    assertEquals(id("s"), (wrapped.hitsAt(-179.0, 2.0).single() as FeatureHit).featureId)
  }
}
