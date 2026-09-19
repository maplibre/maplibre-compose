package org.maplibre.compose.editing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

class FeatureEditorLayersTest {
  private val a = pos(0.0, 0.0)
  private val b = pos(1.0, 0.0)
  private val c = pos(1.0, 1.0)
  private val cursor = pos(5.0, 5.0)

  private fun draft(shape: DrawShape, vararg positions: Position, cursor: Position? = null) =
    EditorDraft(shape, positions.toList(), cursor)

  @Test
  fun point_draft_draws_nothing() {
    assertNull(draftPreviewGeometry(draft(DrawShape.Point)))
    assertNull(draftPreviewGeometry(draft(DrawShape.Point, a, cursor = cursor)))
  }

  @Test
  fun line_draft_needs_two_positions_including_cursor() {
    assertNull(draftPreviewGeometry(draft(DrawShape.LineString)))
    assertNull(draftPreviewGeometry(draft(DrawShape.LineString, a)))
    assertEquals(
      LineString(listOf(a, cursor)),
      draftPreviewGeometry(draft(DrawShape.LineString, a, cursor = cursor)),
    )
    assertEquals(
      LineString(listOf(a, b, cursor)),
      draftPreviewGeometry(draft(DrawShape.LineString, a, b, cursor = cursor)),
    )
    assertEquals(LineString(listOf(a, b)), draftPreviewGeometry(draft(DrawShape.LineString, a, b)))
  }

  @Test
  fun polygon_draft_grows_from_line_to_closed_ring() {
    assertNull(draftPreviewGeometry(draft(DrawShape.Polygon, a)))
    assertEquals(
      LineString(listOf(a, cursor)),
      draftPreviewGeometry(draft(DrawShape.Polygon, a, cursor = cursor)),
    )
    assertEquals(
      Polygon(listOf(listOf(a, b, cursor, a))),
      draftPreviewGeometry(draft(DrawShape.Polygon, a, b, cursor = cursor)),
    )
    assertEquals(
      Polygon(listOf(listOf(a, b, c, a))),
      draftPreviewGeometry(draft(DrawShape.Polygon, a, b, c)),
    )
    assertEquals(
      Polygon(listOf(listOf(a, b, c, cursor, a))),
      draftPreviewGeometry(draft(DrawShape.Polygon, a, b, c, cursor = cursor)),
    )
  }

  @Test
  fun rectangle_draft_uses_second_corner_or_cursor() {
    assertNull(draftPreviewGeometry(draft(DrawShape.Rectangle)))
    assertNull(draftPreviewGeometry(draft(DrawShape.Rectangle, a)))
    val fromCursor =
      assertIs<Polygon>(draftPreviewGeometry(draft(DrawShape.Rectangle, a, cursor = cursor)))
    assertEquals(
      listOf(a, pos(5.0, 0.0), cursor, pos(0.0, 5.0), a),
      fromCursor.coordinates.single(),
    )
    val fromCorner =
      assertIs<Polygon>(draftPreviewGeometry(draft(DrawShape.Rectangle, a, c, cursor = cursor)))
    assertEquals(listOf(a, b, c, pos(0.0, 1.0), a), fromCorner.coordinates.single())
  }

  @Test
  fun sources_split_features_by_selection_in_order() {
    val features = listOf(point("a"), point("b"), point("c"))
    val selection = setOf(id("c"), id("a"))
    assertEquals(listOf(features[1]), unselectedFeatures(features, selection))
    assertEquals(listOf(features[0], features[2]), selectedFeatures(features, selection))
    assertEquals(features, unselectedFeatures(features, emptySet()))
    assertEquals(emptyList(), selectedFeatures(features, emptySet()))
  }

  @Test
  fun handle_features_carry_index_kind_and_draft() {
    val other = object : HandleKind {}
    val handles =
      listOf(
        EditorHandle(HandleKind.Vertex, VertexRef(id("a"), listOf(0)), a),
        EditorHandle(HandleKind.Midpoint, VertexRef(id("a"), listOf(1)), b),
        EditorHandle(HandleKind.Vertex, VertexRef(null, listOf(0)), c),
        EditorHandle(other, null, cursor),
      )
    val features = handleFeatures(handles)
    assertEquals(listOf(a, b, c, cursor), features.map { it.geometry.coordinates })
    assertEquals(listOf(0, 1, 2, 3), features.map { it.properties["index"]!!.jsonPrimitive.int })
    assertEquals(
      listOf("vertex", "midpoint", "vertex", "other"),
      features.map { it.properties["kind"]!!.jsonPrimitive.content },
    )
    assertEquals(
      listOf(false, false, true, false),
      features.map { it.properties["draft"]!!.jsonPrimitive.boolean },
    )
    assertEquals(JsonPrimitive("vertex"), features[0].properties["kind"])
  }
}
