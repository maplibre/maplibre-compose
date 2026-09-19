package org.maplibre.compose.demoapp.demos.featureediting

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.editing.EditStep
import org.maplibre.compose.editing.mapPositions
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
}
