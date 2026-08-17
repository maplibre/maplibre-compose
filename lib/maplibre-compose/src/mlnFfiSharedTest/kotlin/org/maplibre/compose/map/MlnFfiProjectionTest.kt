package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * Off the owner thread, coordinate conversion must be exact under tilt and bearing. Today it
 * answers through the blocking round-trip while native ties projection to the owner thread; once
 * native allows it, the snapshot's frozen projection answers inline. This holds in both modes, so
 * overlays land where the map draws them either way.
 */
class MlnFfiProjectionTest {

  @Test
  fun an_off_thread_projection_round_trips_under_tilt_and_bearing() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.session.setCameraPosition(ROTATED_CAMERA)
      fixture.pumpUntil("the camera to apply and publish a viewport snapshot") {
        val corner = fixture.session.getVisibleRegion().farLeft
        corner.latitude != 0.0 || corner.longitude != 0.0
      }

      // The test thread is not the owner thread, so both calls take the off-thread path.
      val unprojected = fixture.session.positionFromScreenLocation(SCREEN_CENTER)
      assertNear(ROTATED_CAMERA.target, unprojected, "the unprojected screen center")

      val projected = fixture.session.screenLocationFromPosition(ROTATED_CAMERA.target)
      assertTrue(
        abs(projected.x.value - SCREEN_CENTER.x.value) <= PIXEL_TOLERANCE,
        "the projected target x should be ${SCREEN_CENTER.x.value}, was ${projected.x.value}",
      )
      assertTrue(
        abs(projected.y.value - SCREEN_CENTER.y.value) <= PIXEL_TOLERANCE,
        "the projected target y should be ${SCREEN_CENTER.y.value}, was ${projected.y.value}",
      )
    }
  }

  private fun assertNear(expected: Position, actual: Position, what: String) {
    assertTrue(
      abs(expected.latitude - actual.latitude) <= DEGREES_TOLERANCE &&
        abs(expected.longitude - actual.longitude) <= DEGREES_TOLERANCE,
      "$what should be $expected, was $actual",
    )
  }

  private companion object {
    const val DEGREES_TOLERANCE = 1e-6

    const val PIXEL_TOLERANCE = 1.0

    val SCREEN_CENTER = DpOffset(256.dp, 256.dp)

    val ROTATED_CAMERA =
      CameraPosition(target = Position(11.0, 47.0), zoom = 5.0, bearing = 45.0, tilt = 40.0)
  }
}
