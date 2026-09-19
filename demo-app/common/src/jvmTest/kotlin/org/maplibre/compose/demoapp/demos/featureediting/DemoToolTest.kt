package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.editing.EditStep
import org.maplibre.compose.editing.EditorEvent
import org.maplibre.compose.editing.EditorFeature
import org.maplibre.compose.editing.EditorPointer
import org.maplibre.compose.editing.HandleHit
import org.maplibre.compose.editing.HandleKind
import org.maplibre.spatialk.geojson.Feature
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
}
