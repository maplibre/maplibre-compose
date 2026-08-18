package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * Off the owner thread, coordinate conversion uses the snapshot's frozen projection, including
 * under tilt and bearing, so overlays land where the map draws them.
 */
class MlnFfiProjectionTest {

  @Test
  fun an_off_thread_projection_round_trips_under_tilt_and_bearing() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.session.setCameraPosition(ROTATED_CAMERA)
      fixture.pumpUntil("the 512px snapshot to place the screen center on the camera target") {
        val center = fixture.session.positionFromScreenLocation(SCREEN_CENTER)
        abs(fixture.session.getCameraPosition().bearing - ROTATED_CAMERA.bearing) < 0.01 &&
          abs(center.latitude - ROTATED_CAMERA.target.latitude) < COARSE_DEGREES &&
          abs(center.longitude - ROTATED_CAMERA.target.longitude) < COARSE_DEGREES
      }

      // The test thread is not the owner thread, so both calls take the snapshot handle.
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

  @Test
  fun conversions_succeed_while_the_owner_thread_replaces_the_snapshot() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.session.setCameraPosition(START_CAMERA)
      fixture.pumpUntil("the starting camera to apply") {
        abs(fixture.session.getCameraPosition().zoom - START_CAMERA.zoom) < 0.01
      }

      val flight =
        CoroutineScope(Dispatchers.Default).launch {
          fixture.session.animateCameraPosition(ROTATED_CAMERA, 2.seconds)
        }
      fixture.pumpUntil("the camera to start moving") {
        abs(fixture.session.getCameraPosition().zoom - START_CAMERA.zoom) > 0.01
      }

      repeat(200) {
        val unprojected = fixture.session.positionFromScreenLocation(SCREEN_CENTER)
        val projected = fixture.session.screenLocationFromPosition(unprojected)
        assertTrue(
          abs(projected.x.value - SCREEN_CENTER.x.value) <= PIXEL_TOLERANCE &&
            abs(projected.y.value - SCREEN_CENTER.y.value) <= PIXEL_TOLERANCE,
          "a live conversion should round-trip, was $projected from $unprojected",
        )
        fixture.frame()
      }

      fixture.pumpUntil("the flight to finish") { flight.isCompleted }
    }
  }

  private fun assertNear(expected: Position, actual: Position, what: String) {
    assertEquals(
      expected.latitude,
      actual.latitude,
      DEGREES_TOLERANCE,
      "$what latitude",
    )
    assertEquals(
      expected.longitude,
      actual.longitude,
      DEGREES_TOLERANCE,
      "$what longitude",
    )
  }

  private companion object {
    const val DEGREES_TOLERANCE = 1e-6

    /** A 1px startup map at this zoom spans ~0.02°, so the 512px snapshot is past this. */
    const val COARSE_DEGREES = 0.1

    const val PIXEL_TOLERANCE = 1.0

    val SCREEN_CENTER = DpOffset(256.dp, 256.dp)

    val START_CAMERA = CameraPosition(target = Position(11.0, 47.0), zoom = 2.0)

    val ROTATED_CAMERA =
      CameraPosition(target = Position(11.0, 47.0), zoom = 5.0, bearing = 45.0, tilt = 40.0)
  }
}
