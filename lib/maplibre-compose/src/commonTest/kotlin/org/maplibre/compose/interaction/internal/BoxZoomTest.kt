package org.maplibre.compose.interaction.internal

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.internal.boxZoomFit
import org.maplibre.spatialk.geojson.Position

class BoxZoomTest {

  @Test
  fun undersized_or_nonfinite_selections_do_not_project() {
    for (rect in
      listOf(
        DpRect(0.dp, 0.dp, 7.dp, 20.dp),
        DpRect(0.dp, 0.dp, 20.dp, 7.dp),
        DpRect(0.dp, 0.dp, Float.NaN.dp, 20.dp),
      )) {
      assertNull(boxZoomFit(rect, CameraPosition()) { error("unexpected projection") })
    }
  }

  @Test
  fun all_four_projected_corners_determine_the_fit_and_preserve_orientation() {
    val positions =
      mapOf(
        DpOffset(10.dp, 20.dp) to Position(-2.0, 7.0),
        DpOffset(18.dp, 20.dp) to Position(5.0, 9.0),
        DpOffset(18.dp, 28.dp) to Position(8.0, -3.0),
        DpOffset(10.dp, 28.dp) to Position(-4.0, -1.0),
      )
    val fit =
      assertNotNull(
        boxZoomFit(
          DpRect(10.dp, 20.dp, 18.dp, 28.dp),
          CameraPosition(bearing = 25.0, tilt = 40.0),
          positions::get,
        )
      )
    assertEquals(-4.0, fit.bounds.west)
    assertEquals(8.0, fit.bounds.east)
    assertEquals(-3.0, fit.bounds.south)
    assertEquals(9.0, fit.bounds.north)
    assertEquals(25.0, fit.bearing)
    assertEquals(40.0, fit.tilt)
  }

  @Test
  fun unavailable_or_invalid_projection_abandons_the_whole_fit() {
    for (missing in
      listOf(
        DpOffset(0.dp, 0.dp),
        DpOffset(10.dp, 0.dp),
        DpOffset(10.dp, 10.dp),
        DpOffset(0.dp, 10.dp),
      )) {
      assertNull(
        boxZoomFit(DpRect(0.dp, 0.dp, 10.dp, 10.dp), CameraPosition()) {
          if (it == missing) null else Position(0.0, 0.0)
        }
      )
    }
    assertNull(
      boxZoomFit(DpRect(0.dp, 0.dp, 10.dp, 10.dp), CameraPosition()) { Position(Double.NaN, 0.0) }
    )
  }

  @Test
  fun longitudes_are_unwrapped_around_the_camera_target_world_copy() {
    val fit =
      assertNotNull(
        boxZoomFit(
          DpRect(0.dp, 0.dp, 10.dp, 10.dp),
          CameraPosition(target = Position(540.0, 0.0)),
        ) {
          Position(if (it.x == 0.dp) 179.0 else -179.0, 0.0)
        }
      )
    assertEquals(539.0, fit.bounds.west)
    assertEquals(541.0, fit.bounds.east)
  }
}
