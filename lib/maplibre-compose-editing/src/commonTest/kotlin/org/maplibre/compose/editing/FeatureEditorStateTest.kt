package org.maplibre.compose.editing

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon

class FeatureEditorStateTest {
  private fun stateOf(vararg features: EditorFeature, historyLimit: Int = 100) =
    FeatureEditorState(features.toList(), historyLimit = historyLimit)

  @Test
  fun add_assigns_id_and_rejects_present_id() {
    val state = FeatureEditorState(newId = { id("generated") })
    assertEquals(id("generated"), state.add(point()))
    assertEquals(id("generated"), state.features.single().id)
    assertFailsWith<IllegalArgumentException> { state.add(point("generated")) }
  }

  @Test
  fun negative_history_limit_is_rejected() {
    assertFailsWith<IllegalArgumentException> { stateOf(historyLimit = -1) }
  }

  @Test
  fun ids_are_assigned_only_to_accepted_features() {
    var generated = 0
    var rejecting = true
    val validated = mutableListOf<JsonPrimitive?>()
    val state =
      FeatureEditorState(
        validate = {
          validated += it.id
          if (rejecting) "no" else null
        },
        newId = { id("n${generated++}") },
      )
    assertNull(state.add(point()))
    assertNull(state.update(listOf(point(), point("x"))))
    assertEquals(0, generated)
    assertEquals(listOf<JsonPrimitive?>(null, null), validated)
    rejecting = false
    assertEquals(id("n0"), state.add(point()))
    assertEquals(listOf(id("n1")), state.update(listOf(point())))
    assertEquals(listOf<JsonPrimitive?>(null, null, null, null), validated)
  }

  @Test
  fun ids_compare_as_json_primitives() {
    val state =
      FeatureEditorState(
        listOf(
          point().copy(id = JsonPrimitive(1)),
          point().copy(id = JsonPrimitive(1.0)),
          point().copy(id = JsonPrimitive("1")),
        )
      )
    assertEquals(3, state.features.size)
    assertSame(state.features[0], state.feature(JsonPrimitive(1)))
    assertSame(state.features[1], state.feature(JsonPrimitive(1.0)))
    assertSame(state.features[2], state.feature(JsonPrimitive("1")))
    assertFailsWith<IllegalArgumentException> {
      state.load(listOf(point().copy(id = JsonPrimitive(1)), point().copy(id = JsonPrimitive(1))))
    }
  }

  @Test
  fun replace_stores_properties_only_change_without_validation() {
    var calls = 0
    val state =
      FeatureEditorState(
        listOf(point("a")),
        validate = {
          calls++
          null
        },
      )
    val props = buildJsonObject { put("name", "x") }
    assertTrue(state.replace(point("a").copy(properties = props)))
    assertEquals(props, state.feature(id("a"))?.properties)
    assertEquals(0, calls)
    assertTrue(state.replace(point("a", lon = 1.0)))
    assertEquals(1, calls)
    assertFailsWith<IllegalArgumentException> { state.replace(point()) }
    assertFailsWith<IllegalArgumentException> { state.replace(point("missing")) }
  }

  @Test
  fun replace_nulls_bbox_when_geometry_changes_and_bbox_does_not() {
    val bbox = BoundingBox(0.0, 0.0, 1.0, 1.0)
    val state = stateOf(point("a").copy(bbox = bbox))
    assertTrue(state.replace(point("a", lon = 1.0).copy(bbox = bbox)))
    assertNull(state.feature(id("a"))?.bbox)
    val other = BoundingBox(0.0, 0.0, 2.0, 2.0)
    assertTrue(state.replace(point("a", lon = 2.0).copy(bbox = other)))
    assertEquals(other, state.feature(id("a"))?.bbox)
  }

  @Test
  fun validation_rejects_mutation_and_keeps_message() {
    val state =
      FeatureEditorState(
        listOf(point("a")),
        validate = {
          if (it.geometry is Point && (it.geometry as Point).longitude > 5) "too far" else null
        },
      )
    assertFalse(state.replace(point("a", lon = 6.0)))
    assertEquals("too far", state.validationError)
    assertEquals(0.0, (state.feature(id("a"))!!.geometry as Point).longitude)
    assertFalse(state.canUndo)
    assertTrue(state.replace(point("a", lon = 1.0)))
    assertNull(state.validationError)
  }

  @Test
  fun update_is_atomic() {
    val state =
      FeatureEditorState(
        listOf(point("a"), point("b"), point("c")),
        validate = {
          if (it.id == id("b") && (it.geometry as Point).longitude != 0.0) "no" else null
        },
      )
    assertNull(
      state.update(listOf(point("a", lon = 1.0), point("b", lon = 1.0), point("c", lon = 1.0)))
    )
    assertEquals("no", state.validationError)
    assertTrue(state.features.all { (it.geometry as Point).longitude == 0.0 })
    assertFalse(state.canUndo)
  }

  @Test
  fun update_upserts_and_removes_in_one_step() {
    val state = FeatureEditorState(listOf(point("a"), point("b")), newId = { id("n") })
    val ids =
      state.update(listOf(point("a", lon = 1.0), point(), point("z")), removeIds = listOf(id("b")))
    assertEquals(listOf(id("a"), id("n"), id("z")), ids)
    assertEquals(listOf(id("a"), id("n"), id("z")), state.features.map { it.id })
    assertEquals(1.0, (state.feature(id("a"))!!.geometry as Point).longitude)
    state.undo()
    assertEquals(listOf(id("a"), id("b")), state.features.map { it.id })
    assertEquals(0.0, (state.feature(id("a"))!!.geometry as Point).longitude)
    state.redo()
    assertEquals(listOf(id("a"), id("n"), id("z")), state.features.map { it.id })
  }

  @Test
  fun update_with_duplicate_ids_keeps_and_validates_the_last() {
    val state =
      FeatureEditorState(
        listOf(point("a")),
        validate = { if ((it.geometry as Point).longitude == 1.0) "no" else null },
      )
    assertNotNull(
      state.update(
        listOf(
          point("a", lon = 1.0),
          point("a", lon = 2.0),
          point("b", lon = 1.0),
          point("b", lon = 4.0),
        )
      )
    )
    assertNull(state.validationError)
    assertEquals(2.0, (state.feature(id("a"))!!.geometry as Point).longitude)
    assertEquals(4.0, (state.feature(id("b"))!!.geometry as Point).longitude)
    assertEquals(2, state.features.size)
    assertNull(state.update(listOf(point("a", lon = 2.0), point("a", lon = 1.0))))
    assertEquals("no", state.validationError)
  }

  @Test
  fun unchanged_replace_and_update_record_no_step() {
    val state = stateOf(point("a"))
    assertTrue(state.replace(point("a", lon = 1.0)))
    state.undo()
    assertTrue(state.canRedo)
    assertTrue(state.replace(point("a")))
    assertEquals(listOf(id("a")), state.update(listOf(point("a")), removeIds = listOf(id("ghost"))))
    assertFalse(state.canUndo)
    assertTrue(state.canRedo)
    assertEquals(listOf(point("a")), state.features)
  }

  @Test
  fun remove_drops_selection_and_ignores_unknown_ids() {
    val state = stateOf(point("a"), point("b"))
    state.selection = setOf(id("a"), id("b"), id("ghost"))
    assertEquals(setOf(id("a"), id("b")), state.selection)
    state.remove(listOf(id("a"), id("ghost")))
    assertEquals(listOf(id("b")), state.features.map { it.id })
    assertEquals(setOf(id("b")), state.selection)
    state.undo()
    assertEquals(setOf(id("b")), state.selection)
  }

  @Test
  fun load_keeps_draft_and_surviving_selection_and_clears_history() {
    val state = stateOf(point("a"), point("b"))
    state.replace(point("a", lon = 1.0))
    state.selection = setOf(id("a"), id("b"))
    state.draft = EditorDraft(DrawShape.LineString, listOf(pos(0.0, 0.0)))
    state.activeHandle =
      EditorHandle(HandleKind.Vertex, VertexRef(id("b"), emptyList()), pos(0.0, 0.0))
    assertTrue(state.canUndo)
    state.load(listOf(point("b"), point("c")))
    assertEquals(setOf(id("b")), state.selection)
    assertNotNull(state.draft)
    assertNotNull(state.activeHandle)
    assertFalse(state.canRedo)
    state.draft = null
    assertFalse(state.canUndo)
    state.load(listOf(point("c")))
    assertNull(state.activeHandle)
  }

  @Test
  fun move_vertex_keeps_ring_closed_and_nulls_bbox() {
    val bbox = BoundingBox(0.0, 0.0, 10.0, 10.0)
    val state =
      stateOf(square("s").copy(bbox = bbox, geometry = squareGeometry().copy(bbox = bbox)))
    assertTrue(state.moveVertex(VertexRef(id("s"), listOf(0, 0)), pos(-1.0, -1.0)))
    val ring = (state.feature(id("s"))!!.geometry as Polygon).coordinates[0]
    assertEquals(pos(-1.0, -1.0), ring.first())
    assertEquals(pos(-1.0, -1.0), ring.last())
    assertEquals(5, ring.size)
    val exported = state.toFeatureCollection().features.single()
    assertNull(exported.bbox)
    assertNull(exported.geometry.bbox)
  }

  @Test
  fun move_vertex_keeps_altitude_and_updates_active_handle() {
    val state =
      stateOf(line("l", pos(0.0, 0.0), org.maplibre.spatialk.geojson.Position(1.0, 1.0, 50.0)))
    val ref = VertexRef(id("l"), listOf(1))
    state.activeHandle = EditorHandle(HandleKind.Vertex, ref, pos(1.0, 1.0))
    assertTrue(state.moveVertex(ref, pos(2.0, 2.0)))
    val moved = (state.feature(id("l"))!!.geometry as LineString).coordinates[1]
    assertEquals(50.0, moved.altitude)
    assertEquals(2.0, moved.longitude)
    assertEquals(moved, state.activeHandle?.position)
    assertTrue(state.moveVertex(ref, org.maplibre.spatialk.geojson.Position(3.0, 3.0, 7.0)))
    assertEquals(7.0, (state.feature(id("l"))!!.geometry as LineString).coordinates[1].altitude)
  }

  @Test
  fun insert_and_remove_vertex_clear_active_handle_and_enforce_minimums() {
    val state = stateOf(line("l", pos(0.0, 0.0), pos(1.0, 0.0)))
    state.activeHandle =
      EditorHandle(HandleKind.Midpoint, VertexRef(id("l"), listOf(1)), pos(0.5, 0.0))
    assertTrue(state.insertVertex(VertexRef(id("l"), listOf(1)), pos(0.5, 0.5)))
    assertNull(state.activeHandle)
    assertEquals(3, (state.feature(id("l"))!!.geometry as LineString).coordinates.size)
    assertTrue(state.insertVertex(VertexRef(id("l"), listOf(3)), pos(2.0, 0.0)))
    assertEquals(
      pos(2.0, 0.0),
      (state.feature(id("l"))!!.geometry as LineString).coordinates.last(),
    )
    assertFalse(state.insertVertex(VertexRef(id("l"), listOf(5)), pos(2.0, 0.0)))
    state.activeHandle =
      EditorHandle(HandleKind.Vertex, VertexRef(id("l"), listOf(0)), pos(0.0, 0.0))
    assertTrue(state.removeVertex(VertexRef(id("l"), listOf(0))))
    assertNull(state.activeHandle)
    assertTrue(state.removeVertex(VertexRef(id("l"), listOf(0))))
    assertFalse(state.removeVertex(VertexRef(id("l"), listOf(0))))
    assertEquals(2, (state.feature(id("l"))!!.geometry as LineString).coordinates.size)
    val square = stateOf(square("s"))
    assertTrue(square.removeVertex(VertexRef(id("s"), listOf(0, 0))))
    assertFalse(square.removeVertex(VertexRef(id("s"), listOf(0, 0))))
    val point = stateOf(point("p"))
    assertFalse(point.removeVertex(VertexRef(id("p"), emptyList())))
  }

  @Test
  fun active_handle_follows_normalization_rules() {
    val state = stateOf(point("a"), point("b"))
    val handle = EditorHandle(HandleKind.Vertex, VertexRef(id("a"), emptyList()), pos(0.0, 0.0))
    state.activeHandle = handle
    state.selection = setOf(id("a"))
    assertNull(state.activeHandle)
    state.activeHandle = handle
    state.tool = SelectTool()
    assertNull(state.activeHandle)
    state.activeHandle = handle
    state.remove(listOf(id("a")))
    assertNull(state.activeHandle)
    val free = EditorHandle(object : HandleKind {}, null, pos(0.0, 0.0))
    state.activeHandle = free
    state.remove(listOf(id("b")))
    assertSame(free, state.activeHandle)
  }

  @Test
  fun active_handle_and_hover_follow_the_vertex_through_history_update_and_load() {
    val state = stateOf(square("s"))
    state.selection = setOf(id("s"))
    val ref = VertexRef(id("s"), listOf(0, 1))
    val handle = EditorHandle(HandleKind.Vertex, ref, pos(10.0, 0.0))
    state.activeHandle = handle
    state.hover = HandleHit(handle)
    val step = EditStep()
    assertTrue(state.moveVertex(ref, pos(12.0, 1.0), step))
    assertEquals(pos(12.0, 1.0), state.activeHandle?.position)
    assertEquals(pos(12.0, 1.0), (state.hover as HandleHit).handle.position)
    state.undo()
    assertEquals(handle, state.activeHandle)
    assertEquals(HandleHit(handle), state.hover)
    assertTrue(handle in state.handles)
    state.redo()
    assertEquals(pos(12.0, 1.0), state.activeHandle?.position)
    assertTrue(state.activeHandle in state.handles)
    assertNotNull(state.update(listOf(square("s", origin = 5.0))))
    assertEquals(pos(15.0, 5.0), state.activeHandle?.position)
    state.load(listOf(square("s", origin = 1.0)))
    assertEquals(pos(11.0, 1.0), state.activeHandle?.position)
    assertEquals(pos(11.0, 1.0), (state.hover as HandleHit).handle.position)
  }

  @Test
  fun stale_midpoint_and_retargeted_vertex_handles_are_dropped() {
    val state = stateOf(square("s"))
    val midpoint =
      EditorHandle(HandleKind.Midpoint, VertexRef(id("s"), listOf(0, 1)), pos(5.0, 0.0))
    state.activeHandle = midpoint
    state.hover = HandleHit(midpoint)
    assertTrue(state.replace(square("s", origin = 1.0)))
    assertNull(state.activeHandle)
    assertNull(state.hover)
    val step = EditStep()
    assertTrue(state.insertVertex(VertexRef(id("s"), listOf(0, 1)), pos(6.0, -2.0), step))
    state.activeHandle =
      EditorHandle(HandleKind.Vertex, VertexRef(id("s"), listOf(0, 1)), pos(6.0, -2.0))
    assertTrue(state.revert(step))
    assertNull(state.activeHandle)
    state.activeHandle =
      EditorHandle(HandleKind.Vertex, VertexRef(id("s"), listOf(0, 1)), pos(11.0, 1.0))
    state.undo()
    assertEquals(pos(10.0, 0.0), state.activeHandle?.position)
  }

  @Test
  fun hover_is_cleared_when_its_feature_disappears_or_tool_changes() {
    val state = stateOf(point("a"))
    state.hover =
      FeatureHit(id("a"), VertexRef(id("a"), emptyList()), androidx.compose.ui.unit.Dp(0f))
    state.tool = SelectTool()
    assertNull(state.hover)
    state.hover = FeatureHit(id("a"), null, androidx.compose.ui.unit.Dp(0f))
    state.remove(listOf(id("a")))
    assertNull(state.hover)
  }

  @Test
  fun undo_step_merges_consecutive_calls_and_feature_before_reads_gesture_start() {
    val state = stateOf(point("a"))
    val step = EditStep()
    val ref = VertexRef(id("a"), emptyList())
    assertTrue(state.moveVertex(ref, pos(1.0, 0.0), step))
    assertTrue(state.moveVertex(ref, pos(2.0, 0.0), step))
    assertTrue(state.moveVertex(ref, pos(3.0, 0.0), step))
    assertEquals(0.0, (state.featureBefore(step, id("a"))!!.geometry as Point).longitude)
    assertEquals(3.0, (state.featureBefore(EditStep(), id("a"))!!.geometry as Point).longitude)
    assertNull(state.featureBefore(step, id("missing")))
    state.undo()
    assertEquals(0.0, (state.feature(id("a"))!!.geometry as Point).longitude)
    assertFalse(state.canUndo)
    state.redo()
    assertEquals(3.0, (state.feature(id("a"))!!.geometry as Point).longitude)
    assertTrue(state.moveVertex(ref, pos(4.0, 0.0)))
    assertFalse(state.canRedo)
  }

  @Test
  fun revert_works_at_history_limit_zero_and_only_for_the_latest_step() {
    val state = stateOf(point("a"), historyLimit = 0)
    val step = EditStep()
    assertTrue(state.replace(point("a", lon = 1.0), step))
    assertFalse(state.canUndo)
    state.undo()
    assertEquals(1.0, (state.feature(id("a"))!!.geometry as Point).longitude)
    assertFalse(state.revert(EditStep()))
    assertTrue(state.revert(step))
    assertEquals(0.0, (state.feature(id("a"))!!.geometry as Point).longitude)
    assertFalse(state.revert(step))
    assertFalse(state.canRedo)
    assertTrue(state.replace(point("a", lon = 2.0), step))
    assertTrue(state.replace(point("a", lon = 3.0)))
    assertFalse(state.revert(step))
  }

  @Test
  fun revert_clears_the_redo_history() {
    val state = stateOf(point("a"))
    val step = EditStep()
    assertTrue(state.replace(point("a", lon = 1.0), step))
    assertTrue(state.replace(point("a", lon = 2.0)))
    state.undo()
    assertTrue(state.canRedo)
    assertTrue(state.revert(step))
    assertFalse(state.canRedo)
    state.redo()
    assertEquals(0.0, (state.feature(id("a"))!!.geometry as Point).longitude)
  }

  @Test
  fun history_moves_clear_the_validation_error() {
    val state =
      FeatureEditorState(
        listOf(point("a")),
        validate = { if ((it.geometry as Point).longitude > 5) "too far" else null },
      )
    val step = EditStep()
    assertTrue(state.replace(point("a", lon = 1.0), step))
    assertFalse(state.replace(point("a", lon = 6.0), step))
    assertEquals("too far", state.validationError)
    assertTrue(state.revert(step))
    assertNull(state.validationError)
    assertTrue(state.replace(point("a", lon = 1.0)))
    assertFalse(state.replace(point("a", lon = 6.0)))
    state.undo()
    assertNull(state.validationError)
    assertFalse(state.replace(point("a", lon = 6.0)))
    state.redo()
    assertNull(state.validationError)
    assertEquals(1.0, (state.feature(id("a"))!!.geometry as Point).longitude)
  }

  @Test
  fun history_limit_bounds_undo() {
    val state = stateOf(point("a"), historyLimit = 2)
    repeat(5) { state.replace(point("a", lon = it + 1.0)) }
    state.undo()
    state.undo()
    assertEquals(3.0, (state.feature(id("a"))!!.geometry as Point).longitude)
    assertFalse(state.canUndo)
    state.undo()
    assertEquals(3.0, (state.feature(id("a"))!!.geometry as Point).longitude)
  }

  @Test
  fun undo_removes_draft_positions_first_and_is_not_redoable() {
    val state = stateOf(point("a"))
    state.replace(point("a", lon = 1.0))
    state.draft = EditorDraft(DrawShape.LineString, listOf(pos(0.0, 0.0), pos(1.0, 1.0)))
    assertTrue(state.canUndo)
    state.undo()
    assertEquals(listOf(pos(0.0, 0.0)), state.draft?.positions)
    assertFalse(state.canRedo)
    state.undo()
    assertNull(state.draft)
    assertEquals(1.0, (state.feature(id("a"))!!.geometry as Point).longitude)
    state.undo()
    assertEquals(0.0, (state.feature(id("a"))!!.geometry as Point).longitude)
  }

  @Test
  fun draft_vertex_edits_record_no_step_and_skip_validation() {
    val state = FeatureEditorState(validate = { "never" })
    state.draft = EditorDraft(DrawShape.Polygon, listOf(pos(0.0, 0.0), pos(1.0, 0.0)))
    val ref = VertexRef(null, listOf(1))
    state.activeHandle = EditorHandle(HandleKind.Vertex, ref, pos(1.0, 0.0))
    assertTrue(state.moveVertex(ref, pos(2.0, 0.0)))
    assertEquals(pos(2.0, 0.0), state.draft?.positions?.get(1))
    assertEquals(pos(2.0, 0.0), state.activeHandle?.position)
    assertTrue(state.insertVertex(VertexRef(null, listOf(2)), pos(3.0, 0.0)))
    assertEquals(3, state.draft?.positions?.size)
    assertTrue(state.removeVertex(VertexRef(null, listOf(0))))
    assertEquals(listOf(pos(2.0, 0.0), pos(3.0, 0.0)), state.draft?.positions)
    assertNull(state.validationError)
    assertTrue(state.canUndo)
    state.activeHandle = EditorHandle(HandleKind.Vertex, VertexRef(null, listOf(1)), pos(3.0, 0.0))
    state.removeLastDraftPosition()
    assertNull(state.activeHandle)
    state.removeLastDraftPosition()
    assertNull(state.draft)
    assertFalse(state.canUndo)
  }

  @Test
  fun cancel_draft_clears_draft_handle_and_validation_error() {
    val state = FeatureEditorState(listOf(point("a")), validate = { "no" })
    assertFalse(state.replace(point("a", lon = 1.0)))
    state.draft = EditorDraft(DrawShape.Point, emptyList())
    state.activeHandle =
      EditorHandle(object : HandleKind {}, VertexRef(null, listOf(0)), pos(0.0, 0.0))
    val featureHandle =
      EditorHandle(HandleKind.Vertex, VertexRef(id("a"), emptyList()), pos(0.0, 0.0))
    state.cancelDraft()
    assertNull(state.draft)
    assertNull(state.validationError)
    assertNull(state.activeHandle)
    state.activeHandle = featureHandle
    state.cancelDraft()
    assertSame(featureHandle, state.activeHandle)
  }

  @Test
  fun saver_round_trips_features_selection_draft_and_draw_tool() {
    val select = SelectTool()
    val props = buildJsonObject { put("kind", "fence") }
    val state = FeatureEditorState(listOf(square("s").copy(properties = props), point("p")))
    state.selection = setOf(id("p"))
    state.draft = EditorDraft(DrawShape.Polygon, listOf(pos(1.0, 2.0)), cursor = pos(3.0, 4.0))
    state.tool =
      DrawTool(
        DrawShape.Polygon,
        props,
        nextTool = null,
        replaceExisting = true,
        placeOnTap = false,
      )
    val restoreCalls = ArrayList<Pair<EditorTool, EditorDraft?>>()
    val saver =
      FeatureEditorState.saver(
        initialTool = select,
        restoreTool = { tool, draft ->
          restoreCalls += tool to draft
          tool
        },
      )
    val saved = with(saver) { SaverScope { true }.save(state) }
    assertNotNull(saved)
    val restored = saver.restore(saved)!!
    assertEquals(state.features, restored.features)
    assertEquals(setOf(id("p")), restored.selection)
    assertEquals(EditorDraft(DrawShape.Polygon, listOf(pos(1.0, 2.0))), restored.draft)
    val tool = restored.tool as DrawTool
    assertEquals(DrawShape.Polygon, tool.shape)
    assertEquals(props, tool.properties)
    assertNull(tool.nextTool)
    assertTrue(tool.replaceExisting)
    assertFalse(tool.placeOnTap)
    assertTrue(tool.finishOnDoubleTap)
    assertEquals(1, restoreCalls.size)
    assertSame(tool, restoreCalls[0].first)
    assertEquals(restored.draft, restoreCalls[0].second)
    restored.draft = null
    assertFalse(restored.canUndo)
  }

  @Test
  fun saver_restores_initial_tool_when_no_draw_tool_was_active() {
    val select = SelectTool()
    val state = FeatureEditorState(listOf(point("p")), initialTool = DrawTool(DrawShape.Point))
    state.tool = select
    val saver = FeatureEditorState.saver(initialTool = select)
    val restored = saver.restore(with(saver) { SaverScope { true }.save(state) }!!)!!
    assertSame(select, restored.tool)
    val drawing =
      FeatureEditorState(initialTool = DrawTool(DrawShape.LineString, nextTool = SelectTool()))
    val restoredDrawing = saver.restore(with(saver) { SaverScope { true }.save(drawing) }!!)!!
    assertSame(select, (restoredDrawing.tool as DrawTool).nextTool)
  }

  @Test
  fun tool_setter_keeps_draft() {
    val state = stateOf()
    state.draft = EditorDraft(DrawShape.Point, emptyList())
    state.tool = SelectTool()
    assertNotNull(state.draft)
  }

  @Test
  fun handles_derive_from_tool() {
    val handle = EditorHandle(HandleKind.Vertex, null, pos(0.0, 0.0))
    val tool =
      object : EditorTool {
        override fun handles(state: FeatureEditorState) =
          if (state.selection.isEmpty()) emptyList() else listOf(handle)

        override fun onEvent(event: EditorEvent, state: FeatureEditorState) = false
      }
    val state = FeatureEditorState(listOf(point("a")), initialTool = tool)
    assertEquals(emptyList(), state.handles)
    state.selection = setOf(id("a"))
    assertEquals(listOf(handle), state.handles)
  }
}
