package org.maplibre.compose.editing

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.editing.internal.mercatorX
import org.maplibre.compose.editing.internal.mercatorY
import org.maplibre.compose.editing.internal.translatedInMercator
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

class SelectToolTest {
  private val e = Events.flat

  private fun selected(vararg features: EditorFeature, tool: SelectTool = SelectTool()) =
    FeatureEditorState(features.toList(), initialTool = tool).also { state ->
      state.selection = features.mapNotNull { it.id }.toSet()
    }

  private fun FeatureEditorState.ring(id: String): List<Position> =
    (feature(id(id))!!.geometry as Polygon).coordinates[0]

  private fun handleAt(state: FeatureEditorState, lon: Double, lat: Double): HandleHit =
    assertIs<HandleHit>(e.hitAt(state, pos(lon, lat)))

  @Test
  fun vertex_press_claims_and_drag_moves_the_vertex_in_one_step() {
    val state = selected(square("a"))
    val tool = state.tool
    val hit = handleAt(state, 10.0, 0.0)
    assertEquals(HandleKind.Vertex, hit.handle.kind)
    val origin = e.pointer(pos(10.0, 0.0))
    val step = EditStep()
    assertTrue(tool.onEvent(e.press(origin, hit, step), state))
    assertEquals(hit.handle, state.activeHandle)
    tool.onEvent(e.drag(e.pointer(pos(12.0, 1.0)), origin, hit, step), state)
    tool.onEvent(e.drag(e.pointer(pos(13.0, 2.0)), origin, hit, step), state)
    assertEquals(pos(13.0, 2.0), state.ring("a")[1])
    assertEquals(pos(13.0, 2.0), state.activeHandle?.position)
    tool.onEvent(e.release(e.pointer(pos(13.0, 2.0)), step), state)
    state.undo()
    assertEquals(squareGeometry(), state.feature(id("a"))!!.geometry)
    assertFalse(state.canUndo)
  }

  @Test
  fun midpoint_drag_inserts_a_vertex_and_keeps_moving_it() {
    val state = selected(square("a"))
    val tool = state.tool
    val hit = handleAt(state, 5.0, 0.0)
    assertEquals(HandleKind.Midpoint, hit.handle.kind)
    assertEquals(VertexRef(id("a"), listOf(0, 1)), hit.handle.vertex)
    val origin = e.pointer(pos(5.0, 0.0))
    val step = EditStep()
    assertTrue(tool.onEvent(e.press(origin, hit, step), state))
    tool.onEvent(e.drag(e.pointer(pos(5.0, -2.0)), origin, hit, step), state)
    assertEquals(
      EditorHandle(HandleKind.Vertex, VertexRef(id("a"), listOf(0, 1)), pos(5.0, -2.0)),
      state.activeHandle,
    )
    tool.onEvent(e.drag(e.pointer(pos(6.0, -3.0)), origin, hit, step), state)
    val ring = state.ring("a")
    assertEquals(6, ring.size)
    assertEquals(pos(6.0, -3.0), ring[1])
    assertEquals(ring.first(), ring.last())
    state.undo()
    assertEquals(squareGeometry(), state.feature(id("a"))!!.geometry)
  }

  @Test
  fun feature_press_claims_only_for_moving_pointer_types_on_selected_features() {
    val state = selected(square("a"))
    state.add(square("b", origin = 20.0))
    val tool = state.tool
    val hitA = assertIs<FeatureHit>(e.hitAt(state, pos(5.0, 5.0)))
    assertTrue(tool.onEvent(e.press(e.pointer(pos(5.0, 5.0)), hitA), state))
    assertFalse(tool.onEvent(e.press(e.pointer(pos(5.0, 5.0), PointerType.Touch), hitA), state))
    assertFalse(
      tool.onEvent(
        e.press(e.pointer(pos(5.0, 5.0), buttons = setOf(PointerButton.Secondary)), hitA),
        state,
      )
    )
    val hitB = assertIs<FeatureHit>(e.hitAt(state, pos(25.0, 25.0)))
    assertFalse(tool.onEvent(e.press(e.pointer(pos(25.0, 25.0)), hitB), state))
    assertFalse(tool.onEvent(e.press(e.pointer(pos(50.0, 50.0)), null), state))
    val touchMoves = SelectTool(moveSelected = { true })
    assertTrue(
      touchMoves.onEvent(e.press(e.pointer(pos(5.0, 5.0), PointerType.Touch), hitA), state)
    )
  }

  @Test
  fun body_drag_translates_the_selection_in_mercator_from_the_origin() {
    val m = Events.mercator()
    val state = selected(square("a", size = 1.0, originLat = 60.0))
    val tool = state.tool
    val originPosition = pos(0.5, 60.5)
    val hit = assertIs<FeatureHit>(m.hitAt(state, originPosition))
    val origin = m.pointer(originPosition)
    val step = EditStep()
    assertTrue(tool.onEvent(m.press(origin, hit, step), state))
    val originScreen = m.project(originPosition)!!
    val first = m.unproject(DpOffset(originScreen.x + 20.dp, originScreen.y + 40.dp))!!
    tool.onEvent(m.drag(m.pointer(first), origin, hit, step), state)
    val target = m.unproject(DpOffset(originScreen.x + 50.dp, originScreen.y + 100.dp))!!
    tool.onEvent(m.drag(m.pointer(target), origin, hit, step, previous = m.pointer(first)), state)

    val dx = mercatorX(target.longitude) - mercatorX(originPosition.longitude)
    val dy = mercatorY(target.latitude) - mercatorY(originPosition.latitude)
    val expected =
      squareGeometry(size = 1.0, originLat = 60.0).mapPositions { it.translatedInMercator(dx, dy) }
    val ring = state.ring("a")
    expected.coordinates[0].zip(ring).forEach { (want, got) ->
      assertEquals(want.longitude, got.longitude, 1e-9)
      assertEquals(want.latitude, got.latitude, 1e-9)
    }
    assertEquals(ring.first(), ring.last())
    val west = ring.minOf { it.longitude }
    val east = ring.maxOf { it.longitude }
    val south = ring.minOf { it.latitude }
    val north = ring.maxOf { it.latitude }
    assertEquals(1.0, east - west, 1e-9)
    assertEquals(mercatorY(60.0) - mercatorY(61.0), mercatorY(south) - mercatorY(north), 1e-12)
    assertTrue(north - south > 1.0)
    assertTrue(north < 60.0)
    state.undo()
    assertEquals(squareGeometry(size = 1.0, originLat = 60.0), state.feature(id("a"))!!.geometry)
    assertFalse(state.canUndo)
  }

  @Test
  fun body_drag_is_one_validated_step_and_rejects_atomically() {
    val state =
      FeatureEditorState(
        listOf(square("a"), square("b", origin = 20.0, originLat = 0.0)),
        validate = { feature ->
          val south = (feature.geometry as Polygon).coordinates[0].any { it.latitude < 0 }
          if (feature.id == id("b") && south) "b left the northern hemisphere" else null
        },
      )
    state.selection = setOf(id("a"), id("b"))
    val tool = state.tool
    val hit = assertIs<FeatureHit>(e.hitAt(state, pos(5.0, 5.0)))
    val origin = e.pointer(pos(5.0, 5.0))
    val step = EditStep()
    assertTrue(tool.onEvent(e.press(origin, hit, step), state))
    tool.onEvent(e.drag(e.pointer(pos(5.0, 4.0)), origin, hit, step), state)
    assertEquals(squareGeometry(), state.feature(id("a"))!!.geometry)
    assertEquals(squareGeometry(origin = 20.0, originLat = 0.0), state.feature(id("b"))!!.geometry)
    assertEquals("b left the northern hemisphere", state.validationError)
    tool.onEvent(e.drag(e.pointer(pos(5.0, 6.0)), origin, hit, step), state)
    assertNull(state.validationError)
    assertTrue(state.ring("a").all { it.latitude > 0.9 })
    assertTrue(state.ring("b").all { it.latitude > 0.9 })
    state.undo()
    assertEquals(squareGeometry(), state.feature(id("a"))!!.geometry)
    assertFalse(state.canUndo)
  }

  @Test
  fun cancel_reverts_the_gesture() {
    val state = selected(square("a"))
    val tool = state.tool
    val hit = handleAt(state, 10.0, 0.0)
    val origin = e.pointer(pos(10.0, 0.0))
    val step = EditStep()
    tool.onEvent(e.press(origin, hit, step), state)
    tool.onEvent(e.drag(e.pointer(pos(12.0, 1.0)), origin, hit, step), state)
    tool.onEvent(e.cancel(step), state)
    assertEquals(squareGeometry(), state.feature(id("a"))!!.geometry)
    assertFalse(state.canUndo)
  }

  @Test
  fun tap_selects_and_shift_toggles() {
    val state =
      FeatureEditorState(
        listOf(square("a"), square("b", origin = 20.0), square("c", origin = 40.0)),
        initialTool = SelectTool(canSelect = { it.id != id("c") }),
      )
    val tool = state.tool
    val hitA = e.hitAt(state, pos(5.0, 5.0))
    val hitB = e.hitAt(state, pos(25.0, 25.0))
    val hitC = e.hitAt(state, pos(45.0, 45.0))
    assertTrue(tool.onEvent(e.tap(e.pointer(pos(5.0, 5.0)), hitA), state))
    assertEquals(setOf(id("a")), state.selection)
    val shift = setOf(KeyModifier.Shift)
    assertTrue(tool.onEvent(e.tap(e.pointer(pos(25.0, 25.0), modifiers = shift), hitB), state))
    assertEquals(setOf(id("a"), id("b")), state.selection)
    assertTrue(tool.onEvent(e.tap(e.pointer(pos(5.0, 5.0), modifiers = shift), hitA), state))
    assertEquals(setOf(id("b")), state.selection)
    assertTrue(tool.onEvent(e.tap(e.pointer(pos(5.0, 5.0), PointerType.Touch), hitA), state))
    assertEquals(setOf(id("a")), state.selection)
    assertFalse(tool.onEvent(e.tap(e.pointer(pos(45.0, 45.0)), hitC), state))
    assertEquals(setOf(id("a")), state.selection)
  }

  @Test
  fun empty_tap_clears_selection_and_is_never_consumed() {
    val state = selected(square("a"))
    val tool = state.tool
    state.activeHandle = handleAt(state, 0.0, 0.0).handle
    assertFalse(tool.onEvent(e.tap(e.pointer(pos(50.0, 50.0)), null), state))
    assertEquals(emptySet(), state.selection)
    assertNull(state.activeHandle)

    val keeps = selected(square("a"), tool = SelectTool(clearSelectionOnEmptyTap = false))
    assertFalse(keeps.tool.onEvent(e.tap(e.pointer(pos(50.0, 50.0)), null), keeps))
    assertEquals(setOf(id("a")), keeps.selection)
  }

  @Test
  fun secondary_taps_pass_through_except_vertex_removal() {
    val state = selected(square("a"))
    val tool = state.tool
    val secondary = setOf(PointerButton.Secondary)
    val feature = assertIs<FeatureHit>(e.hitAt(state, pos(5.0, 5.0)))
    assertFalse(tool.onEvent(e.tap(e.pointer(pos(5.0, 5.0), buttons = secondary), feature), state))
    assertEquals(setOf(id("a")), state.selection)
    val midpoint = handleAt(state, 5.0, 0.0)
    assertFalse(
      tool.onEvent(e.press(e.pointer(pos(5.0, 0.0), buttons = secondary), midpoint), state)
    )
    assertFalse(tool.onEvent(e.tap(e.pointer(pos(5.0, 0.0), buttons = secondary), midpoint), state))
    // A square keeps its four vertices, so the tap needs a fifth.
    state.insertVertex(VertexRef(id("a"), listOf(0, 1)), pos(5.0, -2.0))
    val vertex = handleAt(state, 5.0, -2.0)
    assertEquals(HandleKind.Vertex, vertex.handle.kind)
    assertTrue(tool.onEvent(e.press(e.pointer(pos(5.0, -2.0), buttons = secondary), vertex), state))
    assertTrue(tool.onEvent(e.tap(e.pointer(pos(5.0, -2.0), buttons = secondary), vertex), state))
    assertEquals(squareGeometry(), state.feature(id("a"))!!.geometry)
    assertNull(state.activeHandle)

    val passes = selected(square("a"), tool = SelectTool(removeVertexOnSecondaryClick = false))
    passes.insertVertex(VertexRef(id("a"), listOf(0, 1)), pos(5.0, -2.0))
    val vertexHit = handleAt(passes, 5.0, -2.0)
    assertFalse(
      passes.tool.onEvent(
        e.press(e.pointer(pos(5.0, -2.0), buttons = secondary), vertexHit),
        passes,
      )
    )
    assertFalse(
      passes.tool.onEvent(e.tap(e.pointer(pos(5.0, -2.0), buttons = secondary), vertexHit), passes)
    )
    assertEquals(6, passes.ring("a").size)
  }

  @Test
  fun handle_tap_sets_active_handle_and_delete_removes_vertex_then_selection() {
    val state = selected(square("a"))
    state.insertVertex(VertexRef(id("a"), listOf(0, 1)), pos(5.0, -2.0))
    val tool = state.tool
    val vertex = handleAt(state, 5.0, -2.0)
    assertTrue(tool.onEvent(e.tap(e.pointer(pos(5.0, -2.0)), vertex), state))
    assertEquals(vertex.handle, state.activeHandle)
    assertFalse(tool.onEvent(e.key(Key.Delete, KeyEventType.KeyUp), state))
    assertTrue(tool.onEvent(e.key(Key.Delete), state))
    assertEquals(squareGeometry(), state.feature(id("a"))!!.geometry)
    assertNull(state.activeHandle)
    assertTrue(tool.onEvent(e.key(Key.Backspace), state))
    assertEquals(emptyList(), state.features)
    assertFalse(tool.onEvent(e.key(Key.Delete), state))

    val keeps = selected(square("a"), tool = SelectTool(removeSelectionOnDelete = false))
    assertFalse(keeps.tool.onEvent(e.key(Key.Delete), keeps))
    assertEquals(1, keeps.features.size)
  }

  @Test
  fun arrow_keys_nudge_the_active_vertex_one_step_per_event() {
    val state = selected(square("a"))
    val tool = state.tool
    state.activeHandle = handleAt(state, 10.0, 0.0).handle
    assertTrue(tool.onEvent(e.key(Key.DirectionRight), state))
    assertEquals(10.1, state.ring("a")[1].longitude, 1e-9)
    assertEquals(0.0, state.ring("a")[1].latitude, 1e-9)
    assertTrue(tool.onEvent(e.key(Key.DirectionUp, modifiers = setOf(KeyModifier.Shift)), state))
    assertEquals(10.1, state.ring("a")[1].longitude, 1e-9)
    assertEquals(1.0, state.ring("a")[1].latitude, 1e-9)
    assertEquals(state.ring("a")[1], state.activeHandle?.position)
    assertTrue(tool.onEvent(e.key(Key.DirectionLeft), state))
    assertTrue(tool.onEvent(e.key(Key.DirectionDown), state))
    assertEquals(10.0, state.ring("a")[1].longitude, 1e-9)
    assertEquals(0.9, state.ring("a")[1].latitude, 1e-9)
    repeat(4) { state.undo() }
    assertEquals(squareGeometry(), state.feature(id("a"))!!.geometry)
    assertFalse(state.canUndo)

    val arrowsToMap = selected(square("a"), tool = SelectTool(nudgeStep = null))
    arrowsToMap.activeHandle = handleAt(arrowsToMap, 10.0, 0.0).handle
    assertFalse(arrowsToMap.tool.onEvent(e.key(Key.DirectionRight), arrowsToMap))
    assertEquals(squareGeometry(), arrowsToMap.feature(id("a"))!!.geometry)
  }

  @Test
  fun escape_clears_the_active_handle_then_the_selection() {
    val state = selected(square("a"))
    val tool = state.tool
    state.activeHandle = handleAt(state, 0.0, 0.0).handle
    assertTrue(tool.onEvent(e.key(Key.Escape), state))
    assertNull(state.activeHandle)
    assertEquals(setOf(id("a")), state.selection)
    assertTrue(tool.onEvent(e.key(Key.Escape), state))
    assertEquals(emptySet(), state.selection)
    assertFalse(tool.onEvent(e.key(Key.Escape), state))
  }

  @Test
  fun handles_are_culled_by_visible_bounds_and_handle_limit() {
    val state = selected(square("a"))
    assertEquals(8, state.handles.size)
    state.visibleBounds = BoundingBox(-1.0, -1.0, 6.0, 6.0)
    assertEquals(
      listOf(
        EditorHandle(HandleKind.Vertex, VertexRef(id("a"), listOf(0, 0)), pos(0.0, 0.0)),
        EditorHandle(HandleKind.Midpoint, VertexRef(id("a"), listOf(0, 1)), pos(5.0, 0.0)),
      ),
      state.handles.filter { it.position.latitude == 0.0 },
    )
    assertEquals(3, state.handles.size)
    assertEquals(VertexRef(id("a"), listOf(0, 4)), state.handles.last().vertex)

    val limited = selected(square("a"), tool = SelectTool(handleLimit = 3))
    assertEquals(emptyList(), limited.handles)
    limited.visibleBounds = BoundingBox(-1.0, -1.0, 6.0, 6.0)
    assertEquals(3, limited.handles.size)
  }

  @Test
  fun handles_follow_per_feature_policies_and_parts() {
    val multi =
      feature(
        MultiLineString(
          listOf(listOf(pos(0.0, 0.0), pos(1.0, 0.0)), listOf(pos(5.0, 0.0), pos(6.0, 0.0)))
        ),
        "m",
      )
    val collection = feature(GeometryCollection(listOf(squareGeometry(origin = 20.0))), "g")
    val state =
      selected(
        square("a"),
        square("b", origin = 40.0),
        square("c", origin = 60.0),
        multi,
        collection,
        tool =
          SelectTool(
            canSelect = { it.id != id("c") },
            editVertices = { it.id != id("a") },
            midpoints = { it.id != id("b") },
          ),
      )
    val byFeature = state.handles.groupBy { it.vertex?.featureId }
    assertEquals(setOf(id("a"), id("b"), id("m")), byFeature.keys)
    assertTrue(byFeature[id("a")]!!.all { it.kind == HandleKind.Midpoint })
    assertEquals(4, byFeature[id("a")]!!.size)
    assertTrue(byFeature[id("b")]!!.all { it.kind == HandleKind.Vertex })
    assertEquals(4, byFeature[id("b")]!!.size)
    assertEquals(
      listOf(
        EditorHandle(HandleKind.Vertex, VertexRef(id("m"), listOf(0, 0)), pos(0.0, 0.0)),
        EditorHandle(HandleKind.Vertex, VertexRef(id("m"), listOf(0, 1)), pos(1.0, 0.0)),
        EditorHandle(HandleKind.Vertex, VertexRef(id("m"), listOf(1, 0)), pos(5.0, 0.0)),
        EditorHandle(HandleKind.Vertex, VertexRef(id("m"), listOf(1, 1)), pos(6.0, 0.0)),
        EditorHandle(HandleKind.Midpoint, VertexRef(id("m"), listOf(0, 1)), pos(0.5, 0.0)),
        EditorHandle(HandleKind.Midpoint, VertexRef(id("m"), listOf(1, 1)), pos(5.5, 0.0)),
      ),
      byFeature[id("m")],
    )
    state.selection = emptySet()
    assertEquals(emptyList(), state.handles)
  }

  @Test
  fun cursor_is_a_hand_over_handles_and_selectable_features() {
    val state =
      FeatureEditorState(
        listOf(square("a"), square("c", origin = 40.0)),
        initialTool = SelectTool(canSelect = { it.id != id("c") }),
      )
    val tool = state.tool
    assertEquals(PointerIcon.Default, tool.cursor(state))
    state.hover = e.hitAt(state, pos(5.0, 5.0))
    assertEquals(PointerIcon.Hand, tool.cursor(state))
    state.hover = e.hitAt(state, pos(45.0, 45.0))
    assertEquals(PointerIcon.Default, tool.cursor(state))
    state.selection = setOf(id("a"))
    state.hover = handleAt(state, 0.0, 0.0)
    assertEquals(PointerIcon.Hand, tool.cursor(state))
    assertNotNull(state.hover)
  }
}
