package org.maplibre.compose.editing

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

class DrawToolTest {
  private val e = Events.flat

  private fun FeatureEditorState.tapAt(lon: Double, lat: Double, count: Int = 1): Boolean {
    val position = pos(lon, lat)
    return tool.onEvent(e.tap(e.pointer(position), e.hitAt(this, position), count), this)
  }

  private fun FeatureEditorState.draftHandleAt(lon: Double, lat: Double): HandleHit {
    val hit = assertIs<HandleHit>(e.hitAt(this, pos(lon, lat)))
    assertNull(hit.handle.vertex?.featureId)
    return hit
  }

  private fun FeatureEditorState.positions(): List<Position>? = draft?.positions

  @Test
  fun taps_place_positions_and_enter_finishes_a_line_with_next_tool() {
    val select = SelectTool()
    val props = buildJsonObject { put("kind", "path") }
    val state = FeatureEditorState(initialTool = DrawTool(DrawShape.LineString, props, select))
    val tool = state.tool
    assertFalse(state.canFinishDraft)
    assertTrue(state.tapAt(0.0, 0.0))
    assertEquals(listOf(pos(0.0, 0.0)), state.positions())
    assertFalse(state.canFinishDraft)
    assertTrue(state.tapAt(1.0, 0.0))
    assertTrue(state.canFinishDraft)
    assertEquals(
      listOf(
        EditorHandle(HandleKind.Vertex, VertexRef(null, listOf(0)), pos(0.0, 0.0)),
        EditorHandle(HandleKind.Vertex, VertexRef(null, listOf(1)), pos(1.0, 0.0)),
      ),
      state.handles,
    )
    assertFalse(tool.onEvent(e.hover(e.pointer(pos(2.0, 2.0)), null), state))
    assertEquals(pos(2.0, 2.0), state.draft?.cursor)
    assertFalse(tool.onEvent(e.hoverEnd(), state))
    assertNull(state.draft?.cursor)
    assertEquals(2, state.positions()?.size)
    assertTrue(tool.onEvent(e.key(Key.Enter), state))
    val created = state.features.single()
    assertEquals(LineString(listOf(pos(0.0, 0.0), pos(1.0, 0.0))), created.geometry)
    assertEquals(props, created.properties)
    assertNotNull(created.id)
    assertEquals(setOf(created.id), state.selection)
    assertSame(select, state.tool)
    assertNull(state.draft)
    assertTrue(state.canUndo)
  }

  @Test
  fun point_creates_a_feature_on_the_first_position_and_keeps_drawing() {
    val tool = DrawTool(DrawShape.Point, nextTool = null)
    val state = FeatureEditorState(initialTool = tool)
    assertTrue(state.tapAt(1.0, 2.0))
    val created = state.features.single()
    assertEquals(Point(pos(1.0, 2.0)), created.geometry)
    assertEquals(setOf(created.id), state.selection)
    assertSame(tool, state.tool)
    assertNull(state.draft)
    assertFalse(state.canFinishDraft)
    assertTrue(state.placeDraftPosition(pos(3.0, 4.0)))
    assertEquals(2, state.features.size)
    assertNull(state.finishDraft())
  }

  @Test
  fun polygon_finishes_on_its_first_vertex_with_a_closed_ring() {
    val state = FeatureEditorState(initialTool = DrawTool(DrawShape.Polygon))
    state.tapAt(0.0, 0.0)
    state.tapAt(10.0, 0.0)
    assertFalse(state.canFinishDraft)
    assertTrue(state.tapAt(0.0, 0.0))
    assertEquals(state.handles[0], state.activeHandle)
    assertEquals(2, state.positions()?.size)
    state.tapAt(10.0, 10.0)
    assertTrue(state.canFinishDraft)
    assertTrue(state.tapAt(10.0, 10.0))
    assertEquals(state.handles[2], state.activeHandle)
    assertEquals(3, state.positions()?.size)
    assertTrue(state.tapAt(0.0, 0.0))
    val polygon = assertIs<Polygon>(state.features.single().geometry)
    assertEquals(
      listOf(pos(0.0, 0.0), pos(10.0, 0.0), pos(10.0, 10.0), pos(0.0, 0.0)),
      polygon.coordinates[0],
    )
    assertIs<SelectTool>(state.tool)
  }

  @Test
  fun line_finishes_on_its_last_vertex_only_when_enabled() {
    val state = FeatureEditorState(initialTool = DrawTool(DrawShape.LineString))
    state.tapAt(0.0, 0.0)
    state.tapAt(10.0, 0.0)
    state.tapAt(20.0, 0.0)
    assertTrue(state.tapAt(0.0, 0.0))
    assertEquals(state.handles[0], state.activeHandle)
    assertEquals(3, state.positions()?.size)
    assertTrue(state.tapAt(20.0, 0.0))
    assertEquals(
      LineString(listOf(pos(0.0, 0.0), pos(10.0, 0.0), pos(20.0, 0.0))),
      state.features.single().geometry,
    )

    val tapsSelect =
      FeatureEditorState(initialTool = DrawTool(DrawShape.LineString, finishOnVertexTap = false))
    tapsSelect.tapAt(0.0, 0.0)
    tapsSelect.tapAt(10.0, 0.0)
    assertTrue(tapsSelect.tapAt(10.0, 0.0))
    assertEquals(tapsSelect.handles[1], tapsSelect.activeHandle)
    assertEquals(emptyList(), tapsSelect.features)
  }

  @Test
  fun double_tap_removes_the_vertex_its_first_tap_placed_and_finishes() {
    val state = FeatureEditorState(initialTool = DrawTool(DrawShape.LineString))
    state.tapAt(0.0, 0.0)
    state.tapAt(10.0, 0.0)
    state.tapAt(20.0, 0.0)
    state.tapAt(30.0, 0.0)
    state.draftHandleAt(30.0, 0.0)
    assertTrue(state.tapAt(30.0, 0.0, count = 2))
    assertEquals(
      LineString(listOf(pos(0.0, 0.0), pos(10.0, 0.0), pos(20.0, 0.0))),
      state.features.single().geometry,
    )
    assertNull(state.draft)

    val keeps =
      FeatureEditorState(initialTool = DrawTool(DrawShape.LineString, finishOnDoubleTap = false))
    keeps.tapAt(0.0, 0.0)
    keeps.tapAt(10.0, 0.0)
    assertTrue(keeps.tapAt(10.0, 0.0, count = 2))
    assertEquals(
      LineString(listOf(pos(0.0, 0.0), pos(10.0, 0.0))),
      keeps.features.single().geometry,
    )

    val short = FeatureEditorState(initialTool = DrawTool(DrawShape.LineString))
    short.tapAt(0.0, 0.0)
    short.tapAt(10.0, 0.0)
    assertTrue(short.tapAt(10.0, 0.0, count = 2))
    assertEquals(listOf(pos(0.0, 0.0), pos(10.0, 0.0)), short.positions())
    assertEquals(emptyList(), short.features)
  }

  @Test
  fun place_on_tap_false_consumes_taps_without_placing() {
    val state = FeatureEditorState(initialTool = DrawTool(DrawShape.LineString, placeOnTap = false))
    assertTrue(state.tapAt(0.0, 0.0))
    assertNull(state.draft)
    assertTrue(state.placeDraftPosition(pos(0.0, 0.0)))
    assertTrue(state.placeDraftPosition(pos(1.0, 0.0)))
    assertTrue(state.tapAt(2.0, 0.0, count = 2))
    assertEquals(listOf(pos(0.0, 0.0), pos(1.0, 0.0)), state.positions())
    assertNotNull(state.finishDraft())
    assertEquals(LineString(listOf(pos(0.0, 0.0), pos(1.0, 0.0))), state.features.single().geometry)
  }

  @Test
  fun draft_handles_false_yields_no_handles() {
    val state =
      FeatureEditorState(initialTool = DrawTool(DrawShape.LineString, draftHandles = false))
    state.tapAt(0.0, 0.0)
    state.tapAt(1.0, 0.0)
    assertEquals(emptyList(), state.handles)
    assertNull(e.hitAt(state, pos(1.0, 0.0)))
    assertTrue(state.tapAt(1.0, 0.0))
    assertEquals(3, state.positions()?.size)
  }

  @Test
  fun draft_handle_drag_moves_the_position_and_cancel_keeps_it() {
    val state = FeatureEditorState(initialTool = DrawTool(DrawShape.LineString))
    val tool = state.tool
    state.tapAt(0.0, 0.0)
    state.tapAt(1.0, 0.0)
    val hit = state.draftHandleAt(1.0, 0.0)
    val origin = e.pointer(pos(1.0, 0.0))
    val step = EditStep()
    assertTrue(tool.onEvent(e.press(origin, hit, step), state))
    assertEquals(hit.handle, state.activeHandle)
    assertFalse(tool.onEvent(e.longPress(origin, hit, step), state))
    tool.onEvent(e.drag(e.pointer(pos(1.0, 1.0)), origin, hit, step), state)
    assertEquals(listOf(pos(0.0, 0.0), pos(1.0, 1.0)), state.positions())
    assertEquals(pos(1.0, 1.0), state.activeHandle?.position)
    tool.onEvent(e.cancel(step), state)
    assertEquals(listOf(pos(0.0, 0.0), pos(1.0, 1.0)), state.positions())
    assertFalse(tool.onEvent(e.press(e.pointer(pos(5.0, 5.0)), null), state))
  }

  @Test
  fun rectangle_mouse_drag_finishes_on_release_and_cancel_discards_it() {
    val tool = DrawTool(DrawShape.Rectangle, nextTool = null)
    val state = FeatureEditorState(initialTool = tool)
    val origin = e.pointer(pos(0.0, 0.0))
    assertFalse(tool.onEvent(e.press(e.pointer(pos(0.0, 0.0), PointerType.Touch), null), state))
    val step = EditStep()
    assertTrue(tool.onEvent(e.press(origin, null, step), state))
    tool.onEvent(e.drag(e.pointer(pos(2.0, 1.0)), origin, null, step), state)
    assertEquals(
      EditorDraft(DrawShape.Rectangle, listOf(pos(0.0, 0.0)), cursor = pos(2.0, 1.0)),
      state.draft,
    )
    tool.onEvent(e.release(e.pointer(pos(3.0, 2.0)), step), state)
    val polygon = assertIs<Polygon>(state.features.single().geometry)
    assertEquals(
      listOf(pos(0.0, 0.0), pos(3.0, 0.0), pos(3.0, 2.0), pos(0.0, 2.0), pos(0.0, 0.0)),
      polygon.coordinates[0],
    )
    assertNull(state.draft)
    assertSame(tool, state.tool)

    val cancelled = EditStep()
    assertTrue(tool.onEvent(e.press(origin, null, cancelled), state))
    tool.onEvent(e.drag(e.pointer(pos(2.0, 1.0)), origin, null, cancelled), state)
    tool.onEvent(e.cancel(cancelled), state)
    assertNull(state.draft)
    assertEquals(1, state.features.size)

    state.placeDraftPosition(pos(0.0, 0.0))
    assertFalse(tool.onEvent(e.press(origin, null), state))
  }

  @Test
  fun rectangle_finishes_on_the_second_tap() {
    val state = FeatureEditorState(initialTool = DrawTool(DrawShape.Rectangle))
    assertTrue(state.tapAt(0.0, 0.0))
    assertFalse(state.canFinishDraft)
    assertTrue(state.tapAt(3.0, 2.0))
    val polygon = assertIs<Polygon>(state.features.single().geometry)
    assertEquals(
      listOf(pos(0.0, 0.0), pos(3.0, 0.0), pos(3.0, 2.0), pos(0.0, 2.0), pos(0.0, 0.0)),
      polygon.coordinates[0],
    )
    assertNull(state.draft)
    assertIs<SelectTool>(state.tool)
  }

  @Test
  fun replace_existing_removes_other_features_in_the_same_step() {
    val state =
      FeatureEditorState(
        listOf(square("a"), square("b", origin = 20.0)),
        initialTool = DrawTool(DrawShape.LineString, replaceExisting = true),
      )
    state.tapAt(50.0, 50.0)
    state.tapAt(51.0, 50.0)
    val created = state.finishDraft()
    assertNotNull(created)
    assertEquals(listOf(created), state.features.map { it.id })
    state.undo()
    assertEquals(listOf(id("a"), id("b")), state.features.map { it.id })
    assertFalse(state.canUndo)
    state.redo()
    assertEquals(listOf(created), state.features.map { it.id })
  }

  @Test
  fun rejected_finish_keeps_the_draft_and_the_error() {
    val tool = DrawTool(DrawShape.LineString)
    var generated = 0
    val state =
      FeatureEditorState(
        initialTool = tool,
        validate = { "too short" },
        newId = { id("n${generated++}") },
      )
    state.tapAt(0.0, 0.0)
    state.tapAt(1.0, 0.0)
    assertTrue(tool.onEvent(e.key(Key.Enter), state))
    assertNull(state.finishDraft())
    assertEquals(listOf(pos(0.0, 0.0), pos(1.0, 0.0)), state.positions())
    assertEquals("too short", state.validationError)
    assertEquals(emptyList(), state.features)
    assertEquals(0, generated)
    assertSame(tool, state.tool)
    assertTrue(state.canUndo)
  }

  @Test
  fun keys_edit_the_draft_and_consume_only_with_a_draft() {
    val state = FeatureEditorState(initialTool = DrawTool(DrawShape.Polygon))
    val tool = state.tool
    assertFalse(tool.onEvent(e.key(Key.Enter), state))
    assertFalse(tool.onEvent(e.key(Key.Escape), state))
    assertFalse(tool.onEvent(e.key(Key.Backspace), state))
    assertFalse(tool.onEvent(e.key(Key.Delete), state))
    state.tapAt(0.0, 0.0)
    state.tapAt(1.0, 0.0)
    state.tapAt(1.0, 1.0)
    assertTrue(tool.onEvent(e.key(Key.Backspace), state))
    assertEquals(listOf(pos(0.0, 0.0), pos(1.0, 0.0)), state.positions())
    assertTrue(tool.onEvent(e.key(Key.Enter), state))
    assertEquals(emptyList(), state.features)
    assertTrue(tool.onEvent(e.key(Key.Escape), state))
    assertNull(state.draft)
    assertFalse(tool.onEvent(e.key(Key.Escape), state))
  }

  @Test
  fun draft_of_another_shape_is_discarded_on_the_first_placement() {
    val state = FeatureEditorState(initialTool = DrawTool(DrawShape.LineString))
    state.draft = EditorDraft(DrawShape.Polygon, listOf(pos(0.0, 0.0), pos(1.0, 0.0)))
    assertTrue(state.tapAt(5.0, 5.0))
    assertEquals(EditorDraft(DrawShape.LineString, listOf(pos(5.0, 5.0))), state.draft)
  }

  @Test
  fun features_are_not_selectable_while_drawing_and_the_cursor_is_a_crosshair() {
    val state =
      FeatureEditorState(listOf(square("a")), initialTool = DrawTool(DrawShape.LineString))
    assertIs<FeatureHit>(e.hitAt(state, pos(5.0, 5.0)))
    assertTrue(state.tapAt(5.0, 5.0))
    assertEquals(listOf(pos(5.0, 5.0)), state.positions())
    assertEquals(emptySet(), state.selection)
    assertEquals(PointerIcon.Crosshair, state.tool.cursor(state))
  }

  @Test
  fun saver_round_trip_restores_a_draw_tool_that_finishes_with_saved_options() {
    val select = SelectTool()
    val props = buildJsonObject { put("kind", "fence") }
    val state = FeatureEditorState(listOf(square("old")), initialTool = select)
    state.tool =
      DrawTool(
        DrawShape.Polygon,
        props,
        nextTool = select,
        finishOnDoubleTap = false,
        replaceExisting = true,
      )
    state.tapAt(20.0, 20.0)
    state.tapAt(30.0, 20.0)
    state.tapAt(30.0, 30.0)
    val restoreCalls = ArrayList<Pair<EditorTool, EditorDraft?>>()
    val saver =
      FeatureEditorState.saver(
        initialTool = select,
        restoreTool = { tool, draft ->
          restoreCalls += tool to draft
          tool
        },
      )
    val restored = saver.restore(with(saver) { SaverScope { true }.save(state) }!!)!!
    val tool = assertIs<DrawTool>(restored.tool)
    assertSame(select, tool.nextTool)
    assertEquals(props, tool.properties)
    assertTrue(tool.replaceExisting)
    assertFalse(tool.finishOnDoubleTap)
    assertEquals(listOf<Pair<EditorTool, EditorDraft?>>(tool to restored.draft), restoreCalls)
    assertEquals(state.draft?.positions, restored.positions())
    assertTrue(restored.canFinishDraft)
    assertTrue(tool.onEvent(e.key(Key.Enter), restored))
    val created = restored.features.single()
    assertEquals(props, created.properties)
    assertEquals(
      listOf(pos(20.0, 20.0), pos(30.0, 20.0), pos(30.0, 30.0), pos(20.0, 20.0)),
      assertIs<Polygon>(created.geometry).coordinates[0],
    )
    assertEquals(setOf(created.id), restored.selection)
    assertSame(select, restored.tool)
  }
}
