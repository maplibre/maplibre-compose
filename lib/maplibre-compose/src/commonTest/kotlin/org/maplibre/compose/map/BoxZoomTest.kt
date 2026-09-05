package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position

class BoxZoomTest {
  @Test
  fun preview_preserves_the_press_origin_and_clears_on_completion() {
    val preview = BoxZoomPreview()
    preview.move(DpOffset(30.dp, 40.dp))
    assertNull(preview.bounds)
    preview.start(DpOffset(20.dp, 30.dp), DpOffset(10.dp, 40.dp))
    assertEquals(DpRect(10.dp, 30.dp, 20.dp, 40.dp), preview.bounds)
    preview.move(DpOffset(40.dp, 10.dp))
    assertEquals(DpRect(20.dp, 10.dp, 40.dp, 30.dp), preview.clear())
    assertNull(preview.bounds)
    preview.move(DpOffset(50.dp, 50.dp))
    assertNull(preview.bounds)
  }

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
      listOf(Position(-2.0, 7.0), Position(5.0, 9.0), Position(8.0, -3.0), Position(-4.0, -1.0))
    val queried = mutableListOf<DpOffset>()
    val fit =
      assertNotNull(
        boxZoomFit(
          DpRect(10.dp, 20.dp, 18.dp, 28.dp),
          CameraPosition(bearing = 25.0, tilt = 40.0),
        ) {
          queried += it
          positions[queried.lastIndex]
        }
      )
    assertEquals(
      listOf(
        DpOffset(10.dp, 20.dp),
        DpOffset(18.dp, 20.dp),
        DpOffset(18.dp, 28.dp),
        DpOffset(10.dp, 28.dp),
      ),
      queried,
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
    for (missing in 0..3) {
      var index = 0
      assertNull(
        boxZoomFit(DpRect(0.dp, 0.dp, 10.dp, 10.dp), CameraPosition()) {
          if (index++ == missing) null else Position(0.0, 0.0)
        }
      )
    }
    assertNull(
      boxZoomFit(DpRect(0.dp, 0.dp, 10.dp, 10.dp), CameraPosition()) { Position(Double.NaN, 0.0) }
    )
  }

  @Test
  fun longitudes_are_unwrapped_around_the_camera_target_world_copy() {
    var index = 0
    val fit =
      assertNotNull(
        boxZoomFit(
          DpRect(0.dp, 0.dp, 10.dp, 10.dp),
          CameraPosition(target = Position(540.0, 0.0)),
        ) {
          Position(if (index++ % 2 == 0) 179.0 else -179.0, 0.0)
        }
      )
    assertEquals(539.0, fit.bounds.west)
    assertEquals(541.0, fit.bounds.east)
  }
}
