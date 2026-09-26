package org.maplibre.compose.demoapp.demos.featureediting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

class FeatureEditingStateTest {
  private val square =
    Shape(
      ShapeKind.Polygon,
      listOf(Position(0.0, 0.0), Position(2.0, 0.0), Position(2.0, 2.0), Position(0.0, 2.0)),
    )

  @Test
  fun draw_is_a_scratch_copy_until_done_and_closes_the_ring_once() {
    val state = FeatureEditingState(square)
    state.draw(ShapeKind.Polygon)
    square.vertices.forEach(state::place)
    assertEquals(square, state.shape)
    state.back()
    assertTrue(state.accept())
    val ring = (state.shape.geometry as Polygon).coordinates.single()
    assertEquals(4, ring.size)
    assertEquals(ring.first(), ring.last())
    state.undo()
    assertEquals(square, state.shape)
    state.redo()
    assertEquals(3, state.shape.vertices.size)
  }

  @Test
  fun cancelling_a_new_shape_preserves_the_document_and_history() {
    val state = FeatureEditingState(square)
    state.replace(square.rotated())
    val rotated = state.shape
    state.draw(ShapeKind.Line)
    state.place(Position(1.0, 1.0))
    state.cancel()
    assertEquals(rotated, state.shape)
    state.undo()
    assertEquals(square, state.shape)
  }

  @Test
  fun drag_frames_form_one_undo_step_and_cancel_restores_the_original() {
    val state = FeatureEditingState(square)
    state.beginEdit()
    state.moveVertex(0, Position(-0.1, 0.0))
    state.moveVertex(0, Position(-0.2, 0.0))
    assertEquals(square, state.shape)
    state.finishDrag()
    assertTrue(state.canUndo)
    state.undo()
    assertEquals(square, state.shape)
    assertFalse(state.canUndo)
    state.beginEdit()
    state.moveVertex(0, Position(-0.3, 0.0))
    state.cancel()
    assertEquals(square, state.shape)
    assertTrue(state.canRedo)
  }

  @Test
  fun invalid_drag_is_visible_as_a_preview_then_rejected_without_an_undo_step() {
    val state = FeatureEditingState(square)
    state.beginEdit()
    state.moveVertex(0, Position(3.0, 1.0))
    assertNotNull(state.displayed.problem())
    state.finishDrag()
    assertEquals(square, state.displayed)
    assertNotNull(state.message)
    assertFalse(state.canUndo)
  }

  @Test
  fun simplify_always_uses_the_original_and_apply_records_one_step() {
    val original = Shape.of(Presets.jfkDrive.geometry)
    val state = FeatureEditingState(original)
    state.simplify(0.8f)
    val reduced = state.displayed
    assertTrue(reduced.vertices.size < original.vertices.size)
    state.simplify(0.2f)
    state.simplify(0.8f)
    assertEquals(reduced, state.displayed)
    state.simplify(0f)
    assertEquals(original, state.displayed)
    state.simplify(0.8f)
    assertTrue(state.accept())
    state.undo()
    assertEquals(original, state.shape)
    assertFalse(state.canUndo)
  }

  @Test
  fun invalid_or_collapsed_shapes_are_not_committed() {
    val state = FeatureEditingState(square)
    assertFalse(state.replace(square.copy(vertices = square.vertices.take(2))))
    assertFalse(
      state.replace(
        Shape(ShapeKind.Polygon, listOf(Position(0.0, 0.0), Position(1.0, 0.0), Position(2.0, 0.0)))
      )
    )
    assertEquals(square, state.shape)
    assertFalse(state.canUndo)
  }

  @Test
  fun large_rings_are_checked_instead_of_silently_accepted() {
    val bowtie =
      listOf(Position(0.0, 0.0), Position(2.0, 2.0), Position(0.0, 2.0), Position(2.0, 0.0))
    val dense =
      bowtie.indices.flatMap { i ->
        val a = bowtie[i]
        val b = bowtie[(i + 1) % bowtie.size]
        (0 until 41).map { step ->
          val fraction = step / 41.0
          Position(
            a.longitude + (b.longitude - a.longitude) * fraction,
            a.latitude + (b.latitude - a.latitude) * fraction,
          )
        }
      }
    assertEquals(164, dense.size)
    assertEquals(
      "The outline crosses or touches itself.",
      Shape(ShapeKind.Polygon, dense).problem(),
    )
    assertNull(square.problem())
    assertNull(Shape.of(Presets.goldenGatePark.geometry).problem())
    assertNull(Shape.of(Presets.conservatoryCircle.geometry).problem())
  }

  @Test
  fun insertion_and_removal_are_undoable_and_new_edits_clear_redo() {
    val state = FeatureEditingState(square)
    state.selectedVertex = 0
    state.insertVertex(Position(1.0, 0.0))
    val inserted = state.shape
    assertEquals(5, inserted.vertices.size)
    state.selectedVertex = 1
    state.removeVertex()
    assertEquals(square, state.shape)
    state.undo()
    assertEquals(inserted, state.shape)
    assertTrue(state.canRedo)
    state.replace(square.rotated())
    assertFalse(state.canRedo)
  }
}
