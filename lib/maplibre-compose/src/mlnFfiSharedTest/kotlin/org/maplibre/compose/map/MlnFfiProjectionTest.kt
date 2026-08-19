package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
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
      fixture.pumpUntil("the camera target to land on the screen center") {
        val camera = fixture.session.getCameraPosition()
        val projected = fixture.session.screenLocationFromPosition(camera.target)
        abs(camera.bearing - ROTATED_CAMERA.bearing) < 0.01 &&
          abs(camera.zoom - ROTATED_CAMERA.zoom) < 0.01 &&
          abs(camera.tilt - ROTATED_CAMERA.tilt) < 0.01 &&
          projected.isNear(SCREEN_CENTER)
      }

      // The test thread is not the owner thread, so both calls take the snapshot handle.
      val camera = fixture.session.getCameraPosition()
      val projected = fixture.session.screenLocationFromPosition(camera.target)
      assertTrue(
        projected.isNear(SCREEN_CENTER),
        "the camera target ${camera.target} should project to $SCREEN_CENTER ± $PIXEL_TOLERANCE, was $projected",
      )

      val roundTrip =
        fixture.session.screenLocationFromPosition(
          fixture.session.positionFromScreenLocation(SCREEN_CENTER)
        )
      assertTrue(
        roundTrip.isNear(SCREEN_CENTER),
        "the screen center $SCREEN_CENTER should round-trip, was $roundTrip",
      )
    }
  }

  @Test
  fun a_resize_reprojects_the_camera_target_to_the_new_center() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.session.setCameraPosition(START_CAMERA)
      fixture.pumpUntil("the camera target to land on the first screen center") {
        val projected = fixture.session.screenLocationFromPosition(START_CAMERA.target)
        abs(fixture.session.getCameraPosition().zoom - START_CAMERA.zoom) < 0.01 &&
          projected.isNear(SCREEN_CENTER)
      }

      val movedBefore = fixture.events.count { it == "cameraMoved" }
      fixture.hasRendered = false
      fixture.pumpUntil("the resized map to render", extent = WIDE_EXTENT) { fixture.hasRendered }
      fixture.pumpUntil("the camera target to land on the resized screen center") {
        fixture.session.screenLocationFromPosition(START_CAMERA.target).isNear(WIDE_SCREEN_CENTER)
      }

      assertTrue(
        fixture.events.count { it == "cameraMoved" } > movedBefore,
        "a resize should report cameraMoved so Compose overlays re-read the projection",
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
          projected.isNear(SCREEN_CENTER),
          "a live conversion should round-trip, was $projected from $unprojected",
        )
        fixture.frame()
      }

      fixture.pumpUntil("the flight to finish") { flight.isCompleted }
    }
  }

  private fun DpOffset.isNear(other: DpOffset): Boolean =
    abs(x.value - other.x.value) <= PIXEL_TOLERANCE &&
      abs(y.value - other.y.value) <= PIXEL_TOLERANCE

  private companion object {
    const val PIXEL_TOLERANCE = 1.0

    val SCREEN_CENTER = DpOffset(256.dp, 256.dp)

    val WIDE_EXTENT: MapExtent = MapExtent.fromLogical(width = 640, height = 512, scaleFactor = 1.0)

    val WIDE_SCREEN_CENTER = DpOffset(320.dp, 256.dp)

    val START_CAMERA = CameraPosition(target = Position(11.0, 47.0), zoom = 2.0)

    val ROTATED_CAMERA =
      CameraPosition(target = Position(11.0, 47.0), zoom = 5.0, bearing = 45.0, tilt = 40.0)
  }
}
