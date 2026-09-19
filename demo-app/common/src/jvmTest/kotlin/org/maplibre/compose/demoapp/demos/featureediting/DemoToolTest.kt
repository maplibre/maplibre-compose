package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.editing.EditStep
import org.maplibre.compose.editing.EditorEvent
import org.maplibre.compose.editing.EditorFeature
import org.maplibre.compose.editing.EditorPointer
import org.maplibre.compose.editing.HandleHit
import org.maplibre.compose.editing.HandleKind
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/** A flat map: one dp is a tenth of a degree, y grows south. */
private fun project(position: Position): DpOffset =
  DpOffset((position.longitude * 10).dp, (-position.latitude * 10).dp)

private fun unproject(screen: DpOffset): Position =
  Position(screen.x.value / 10.0, -screen.y.value / 10.0)

private fun square(id: String, origin: Double = 0.0, size: Double = 10.0): EditorFeature =
  Feature(
    Polygon(
      listOf(
        listOf(
          Position(origin, origin),
          Position(origin + size, origin),
          Position(origin + size, origin + size),
          Position(origin, origin + size),
          Position(origin, origin),
        )
      )
    ),
    properties = null,
    id = JsonPrimitive(id),
  )

private fun touch(position: Position) =
  EditorPointer(project(position), position, PointerType.Touch, emptySet(), emptySet())

class DemoToolTest {
  private fun stateWith(vararg features: EditorFeature): FeatureEditingState {
    val demo = FeatureEditingState()
    val editor = demo.editor
    editor.remove(editor.features.mapNotNull { it.id })
    editor.update(features.toList(), undoStep = EditStep())
    editor.selection = setOf(JsonPrimitive("a"))
    return demo
  }

  private fun FeatureEditingState.ring(id: String): List<Position> =
    (editor.feature(JsonPrimitive(id))!!.geometry as Polygon).coordinates[0]

  @Test
  fun a_dragged_corner_does_not_snap_to_its_own_shape() {
    val demo = stateWith(square("a"))
    val editor = demo.editor
    val corner = Position(10.0, 0.0)
    val hit = assertIs<HandleHit>(editor.hitTest(project(corner), 2.dp, ::unproject).first())
    assertEquals(HandleKind.Vertex, hit.handle.kind)
    val origin = touch(corner)
    val step = EditStep()
    assertTrue(
      editor.tool.onEvent(EditorEvent.Press(origin, hit, step, ::project, ::unproject), editor)
    )
    // One degree, ten dp, from the neighbouring corner: inside the 16 dp touch snap radius.
    val target = Position(10.0, 9.0)
    editor.tool.onEvent(
      EditorEvent.Drag(touch(target), origin, origin, hit, step, ::project, ::unproject),
      editor,
    )
    assertNull(editor.validationError)
    assertNull(demo.snapTarget)
    val ring = demo.ring("a")
    assertEquals(target, ring[1])
    assertTrue(ring.zipWithNext().none { (a, b) -> a == b })
  }

  @Test
  fun a_dragged_corner_snaps_to_another_shape() {
    val demo = stateWith(square("a"), square("b", origin = 20.0))
    val editor = demo.editor
    val corner = Position(10.0, 10.0)
    val hit = assertIs<HandleHit>(editor.hitTest(project(corner), 2.dp, ::unproject).first())
    val origin = touch(corner)
    val step = EditStep()
    editor.tool.onEvent(EditorEvent.Press(origin, hit, step, ::project, ::unproject), editor)
    editor.tool.onEvent(
      EditorEvent.Drag(
        touch(Position(19.0, 19.0)),
        origin,
        origin,
        hit,
        step,
        ::project,
        ::unproject,
      ),
      editor,
    )
    assertEquals(Position(20.0, 20.0), demo.snapTarget)
    assertEquals(Position(20.0, 20.0), demo.ring("a")[2])
  }

  @Test
  fun a_tap_leaves_no_snap_target() {
    val demo = stateWith(square("a"), square("b", origin = 20.0))
    val editor = demo.editor
    val near = Position(19.0, 19.0)
    editor.tool.onEvent(
      EditorEvent.Tap(touch(near), null, 1, EditStep(), ::project, ::unproject),
      editor,
    )
    assertNull(demo.snapTarget)
  }

  @Test
  fun the_snap_ring_clears_when_the_pointer_leaves_the_map_or_the_tool_changes() {
    val demo = stateWith(square("a"), square("b", origin = 20.0))
    val editor = demo.editor
    demo.use(demo.polygonTool)
    editor.placeDraftPosition(Position(0.0, 0.0))
    val hover = EditorEvent.Hover(touch(Position(19.0, 19.0)), null, ::project, ::unproject)
    editor.tool.onEvent(hover, editor)
    assertEquals(Position(20.0, 20.0), demo.snapTarget)
    editor.tool.onEvent(EditorEvent.HoverEnd(::project, ::unproject), editor)
    assertNull(demo.snapTarget)
    editor.tool.onEvent(hover, editor)
    assertEquals(Position(20.0, 20.0), demo.snapTarget)
    demo.use(demo.selectTool)
    assertNull(demo.snapTarget)
  }

  private fun FeatureEditingState.frameHandle(kind: FrameHandle): HandleHit =
    HandleHit(editor.handles.single { it.kind == kind })

  @Test
  fun a_frame_handle_held_past_the_long_press_still_drags() {
    val demo = stateWith(square("a"))
    val editor = demo.editor
    val original = editor.feature(JsonPrimitive("a"))!!.geometry
    val hit = demo.frameHandle(FrameHandle.Rotate)
    val origin = touch(hit.handle.position)
    val step = EditStep()
    assertTrue(
      editor.tool.onEvent(EditorEvent.Press(origin, hit, step, ::project, ::unproject), editor)
    )
    assertFalse(
      editor.tool.onEvent(EditorEvent.LongPress(origin, hit, step, ::project, ::unproject), editor)
    )
    val target = touch(Position(10.0, 0.0))
    editor.tool.onEvent(
      EditorEvent.Drag(target, origin, origin, hit, step, ::project, ::unproject),
      editor,
    )
    assertNotEquals(original, editor.feature(JsonPrimitive("a"))!!.geometry)
    assertEquals(FrameHandle.Rotate, demo.frameGesture?.kind)
    editor.tool.onEvent(EditorEvent.Release(target, step, ::project, ::unproject), editor)
    assertNull(demo.frameGesture)
  }

  @Test
  fun a_frame_handle_held_and_lifted_activates_nothing() {
    val demo = stateWith(square("a"))
    val editor = demo.editor
    val hit = demo.frameHandle(FrameHandle.Rotate)
    val origin = touch(hit.handle.position)
    val step = EditStep()
    editor.tool.onEvent(EditorEvent.Press(origin, hit, step, ::project, ::unproject), editor)
    editor.tool.onEvent(EditorEvent.LongPress(origin, hit, step, ::project, ::unproject), editor)
    editor.tool.onEvent(EditorEvent.Tap(origin, hit, 1, step, ::project, ::unproject), editor)
    assertNull(demo.frameGesture)
    assertNull(editor.activeHandle)
  }

  @Test
  fun switching_tools_takes_back_a_frame_gesture_in_progress() {
    val demo = stateWith(square("a"))
    val editor = demo.editor
    val original = editor.feature(JsonPrimitive("a"))!!.geometry
    val hit = demo.frameHandle(FrameHandle.Rotate)
    val origin = touch(hit.handle.position)
    val step = EditStep()
    editor.tool.onEvent(EditorEvent.Press(origin, hit, step, ::project, ::unproject), editor)
    editor.tool.onEvent(
      EditorEvent.Drag(
        touch(Position(10.0, 0.0)),
        origin,
        origin,
        hit,
        step,
        ::project,
        ::unproject,
      ),
      editor,
    )
    demo.use(demo.polygonTool)
    assertNull(demo.frameGesture)
    assertEquals(original, editor.feature(JsonPrimitive("a"))!!.geometry)
    assertSame(demo.polygonTool, editor.tool)
  }

  @Test
  fun the_station_of_a_two_point_line_has_no_midpoint_handle_under_it() {
    val line =
      Feature(
        LineString(Position(0.0, 0.0), Position(10.0, 0.0)),
        properties = null,
        id = JsonPrimitive("a"),
      )
    val demo = stateWith(line)
    val handles = demo.editor.handles
    val station = handles.single { it.kind == FrameHandle.Station }
    assertTrue(handles.none { it.kind == HandleKind.Midpoint })
    assertEquals(2, handles.count { it.kind == HandleKind.Vertex })
    assertEquals(5.0, station.position.longitude, 0.01)
  }
}
