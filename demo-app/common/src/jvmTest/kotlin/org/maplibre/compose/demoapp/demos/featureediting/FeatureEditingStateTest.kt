package org.maplibre.compose.demoapp.demos.featureediting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.editing.EditStep
import org.maplibre.compose.editing.mapPositions
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.turf.measurement.computeBbox

class FeatureEditingStateTest {
  @Test
  fun loading_a_present_preset_returns_the_bounds_of_the_stored_shape() {
    val demo = FeatureEditingState()
    val editor = demo.editor
    val park = Presets.goldenGatePark
    val moved =
      park.copy(
        geometry = park.geometry.mapPositions { Position(it.longitude + 1, it.latitude + 1) },
        bbox = null,
      )
    editor.update(listOf(moved), undoStep = EditStep())
    assertEquals(moved.geometry.computeBbox(), demo.loadPreset(park))
    assertEquals(moved.geometry, editor.feature(park.id!!)!!.geometry)
    val union = demo.loadAllPresets()
    assertEquals(moved.geometry.computeBbox().north, union.north)
    assertEquals(moved.geometry.computeBbox().east, union.east)
  }

  @Test
  fun selecting_from_the_panel_returns_to_the_select_tool() {
    val demo = FeatureEditingState()
    val editor = demo.editor
    val park = checkNotNull(Presets.goldenGatePark.id)
    demo.use(demo.polygonTool)
    editor.placeDraftPosition(Position(0.0, 0.0))
    assertEquals(emptySet(), editor.selection)
    demo.select(park)
    assertSame(demo.selectTool, editor.tool)
    assertEquals(setOf(park), editor.selection)
    assertNull(editor.draft)
  }

  @Test
  fun a_rejected_last_simplify_frame_leaves_no_error_at_release() {
    val demo = FeatureEditingState()
    val editor = demo.editor
    val id = JsonPrimitive("hook")
    // Douglas-Peucker on this ring drops a corner at 0.9 and crosses itself at 0.925.
    val ring =
      listOf(
        Position(10.0, 3.0),
        Position(2.0, 0.0),
        Position(1.0, 1.0),
        Position(2.0, 5.0),
        Position(4.0, 7.0),
        Position(2.0, 3.0),
        Position(7.0, 3.0),
        Position(10.0, 3.0),
      )
    val original = Polygon(listOf(ring))
    editor.update(listOf(Feature(original, properties = null, id = id)), undoStep = EditStep())
    demo.select(id)
    demo.scrubSimplify(0.9f)
    assertNull(editor.validationError)
    val accepted = checkNotNull(editor.feature(id)).geometry
    assertNotEquals(original, accepted)
    demo.scrubSimplify(0.925f)
    assertEquals(SELF_CROSSING_MESSAGE, editor.validationError)
    assertEquals(accepted, checkNotNull(editor.feature(id)).geometry)
    demo.finishSimplify()
    assertNull(editor.validationError)
    assertNull(demo.simplifyScrub)
    assertEquals(accepted, checkNotNull(editor.feature(id)).geometry)
    editor.undo()
    assertEquals(original, checkNotNull(editor.feature(id)).geometry)
  }
}
