package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.toLatLng
import org.maplibre.nativeffi.geo.ScreenPoint
import org.maplibre.spatialk.geojson.Position

/**
 * Off the owner thread, coordinate conversion uses the snapshot's frozen projection, including
 * under tilt and bearing, so overlays land where the map draws them.
 */
class MlnFfiProjectionTest {

  @Test
  fun axonometric_gestures_respect_camera_padding() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.session.setCameraPosition(ROTATED_CAMERA)
      fixture.session.setCameraPadding(PaddingValues(start = CAMERA_PADDING_DP.dp))
      fixture.session.setRenderSettings(
        RenderOptions(cameraProjection = CameraProjection.Axonometric())
      )
      fixture.pumpUntil("the padded axonometric projection to apply") {
        val camera = fixture.session.getCameraPosition()
        fixture.session.readMap {
          it.size.width == 512 &&
            it.size.height == 512 &&
            it.projectionMode.axonometric == true &&
            it.camera.padding?.left == CAMERA_PADDING_DP &&
            it.pixelForLatLng(ROTATED_CAMERA.target.toLatLng()).isNear(PADDED_CENTER) &&
            camera.target.isNear(ROTATED_CAMERA.target) &&
            camera.zoom == ROTATED_CAMERA.zoom &&
            camera.bearing == ROTATED_CAMERA.bearing &&
            camera.tilt == ROTATED_CAMERA.tilt
        } == true
      }

      var landmark = assertNotNull(fixture.session.readMap { it.latLngForPixel(PADDED_CENTER) })
      val cameraBefore = assertNotNull(fixture.session.readMap { it.camera.center })
      fixture.session.moveBy(PAN_X_DP, PAN_Y_DP, duration = Duration.ZERO)
      fixture.pumpUntil("the axonometric camera to move") {
        fixture.session.readMap { it.camera.center != cameraBefore } ?: false
      }

      val expected = ScreenPoint(x = PADDED_CENTER.x + PAN_X_DP, y = PADDED_CENTER.y + PAN_Y_DP)
      var actual = fixture.session.readMap { it.pixelForLatLng(landmark) }
      assertTrue(
        actual.isNear(expected),
        "the landmark should follow the pan to $expected ± $PIXEL_TOLERANCE, was $actual",
      )

      landmark = assertNotNull(fixture.session.readMap { it.latLngForPixel(PADDED_CENTER) })
      val bearingBefore = assertNotNull(fixture.session.readMap { it.camera.bearing })
      fixture.session.rotateAndPitchBy(
        bearingDelta = 15.0,
        pitchDelta = 5.0,
        duration = Duration.ZERO,
      )
      fixture.pumpUntil("the axonometric camera to orbit") {
        fixture.session.readMap { abs((it.camera.bearing ?: 0.0) - bearingBefore) > 1.0 } ?: false
      }

      actual = fixture.session.readMap { it.pixelForLatLng(landmark) }
      assertTrue(
        actual.isNear(PADDED_CENTER),
        "the padded center $PADDED_CENTER should stay fixed during orbit, was $actual",
      )
    }
  }

  @Test
  fun axonometric_camera_updates_respect_camera_padding() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.session.setCameraPosition(ROTATED_CAMERA)
      fixture.session.setCameraPadding(PaddingValues(start = CAMERA_PADDING_DP.dp))
      fixture.session.setRenderSettings(
        RenderOptions(cameraProjection = CameraProjection.Axonometric())
      )
      fixture.pumpUntil("the initial target to reach the padded center") {
        fixture.session.readMap {
          it.projectionMode.axonometric == true &&
            it.pixelForLatLng(ROTATED_CAMERA.target.toLatLng()).isNear(PADDED_CENTER)
        } == true
      }

      fixture.session.setCameraPosition(REPLACEMENT_CAMERA)
      fixture.pumpUntil("the replacement target to reach the padded center") {
        fixture.session.getCameraPosition().target.isNear(REPLACEMENT_CAMERA.target) &&
          fixture.session.readMap {
            it.pixelForLatLng(REPLACEMENT_CAMERA.target.toLatLng()).isNear(PADDED_CENTER)
          } == true
      }

      fixture.session.setCameraPadding(PaddingValues(end = REPLACEMENT_PADDING_DP.dp))
      fixture.pumpUntil("the replacement padding to move the camera target") {
        fixture.session.readMap {
          it.camera.padding?.right == REPLACEMENT_PADDING_DP &&
            it
              .pixelForLatLng(REPLACEMENT_CAMERA.target.toLatLng())
              .isNear(REPLACEMENT_PADDED_CENTER)
        } == true
      }
      assertTrue(fixture.session.getCameraPosition().target.isNear(REPLACEMENT_CAMERA.target))

      fixture.session.setRenderSettings(
        RenderOptions(cameraProjection = CameraProjection.Perspective)
      )
      fixture.pumpUntil("perspective mode to preserve the padded camera target") {
        fixture.session.readMap {
          it.projectionMode.axonometric == false &&
            it
              .pixelForLatLng(REPLACEMENT_CAMERA.target.toLatLng())
              .isNear(REPLACEMENT_PADDED_CENTER)
        } == true
      }
      assertTrue(fixture.session.getCameraPosition().target.isNear(REPLACEMENT_CAMERA.target))
    }
  }

  @Test
  fun axonometric_gestures_keep_the_current_world_copy() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.session.setCameraPosition(ANTIMERIDIAN_CAMERA)
      fixture.session.setCameraPadding(PaddingValues(start = CAMERA_PADDING_DP.dp))
      fixture.session.setRenderSettings(
        RenderOptions(cameraProjection = CameraProjection.Axonometric())
      )
      fixture.pumpUntil("the noncanonical world copy to apply") {
        fixture.session.readMap { it.projectionMode.axonometric == true } == true
      }

      val token = fixture.session.onGestureStarted()
      val movement =
        CoroutineScope(Dispatchers.Default).launch {
          fixture.session.moveByAwaitingTransition(
            WORLD_COPY_PAN_X_DP,
            0.0,
            WORLD_COPY_ANIMATION_MS.milliseconds,
            token,
          )
          fixture.session.onGestureEnded(token)
        }
      var minimumAbsoluteLongitude = 180.0
      fixture.pumpUntil("the noncanonical world copy to move") {
        minimumAbsoluteLongitude =
          minOf(
            minimumAbsoluteLongitude,
            abs(fixture.session.getCameraPosition().target.longitude),
          )
        movement.isCompleted
      }

      assertTrue(
        minimumAbsoluteLongitude > 150.0,
        "the animated pan should cross the antimeridian instead of traversing longitude $minimumAbsoluteLongitude",
      )
    }
  }

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
          assertNotNull(fixture.session.positionFromScreenLocation(SCREEN_CENTER))
        )
      assertTrue(
        roundTrip.isNear(SCREEN_CENTER),
        "the screen center $SCREEN_CENTER should round-trip, was $roundTrip",
      )
    }
  }

  @Test
  fun consecutive_one_pixel_resizes_keep_the_camera_target() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.session.setCameraPosition(START_CAMERA)
      fixture.pumpUntil("the starting camera to apply") {
        abs(fixture.session.getCameraPosition().zoom - START_CAMERA.zoom) < 0.01 &&
          fixture.session.screenLocationFromPosition(START_CAMERA.target).isNear(SCREEN_CENTER)
      }

      val start = fixture.session.getCameraPosition().target
      for (width in 200..210) {
        val extent = MapExtent.fromLogical(width, 200, scaleFactor = 1.0)
        fixture.hasRendered = false
        fixture.pumpUntil(
          "the map to render at ${extent.width}x${extent.height}",
          extent = extent,
        ) {
          fixture.hasRendered
        }
        val camera = fixture.session.getCameraPosition()
        assertTrue(
          abs(camera.target.latitude - start.latitude) < TARGET_TOLERANCE &&
            abs(camera.target.longitude - start.longitude) < TARGET_TOLERANCE,
          "resize to ${extent.width}x${extent.height} moved the camera from $start to ${camera.target}",
        )
        val projected = fixture.session.screenLocationFromPosition(start)
        val expectedCenter = DpOffset((width / 2.0).dp, 100.dp)
        assertTrue(
          projected.isNear(expectedCenter),
          "the camera target should stay at the visual center $expectedCenter ± $PIXEL_TOLERANCE, was $projected",
        )
      }
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
        val unprojected = assertNotNull(fixture.session.positionFromScreenLocation(SCREEN_CENTER))
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

  private fun DpOffset?.isNear(other: DpOffset): Boolean =
    this != null &&
      abs(x.value - other.x.value) <= PIXEL_TOLERANCE &&
      abs(y.value - other.y.value) <= PIXEL_TOLERANCE

  private fun ScreenPoint?.isNear(other: ScreenPoint): Boolean =
    this != null && abs(x - other.x) <= PIXEL_TOLERANCE && abs(y - other.y) <= PIXEL_TOLERANCE

  private fun Position.isNear(other: Position): Boolean =
    abs(longitude - other.longitude) <= TARGET_TOLERANCE &&
      abs(latitude - other.latitude) <= TARGET_TOLERANCE

  private companion object {
    const val PIXEL_TOLERANCE = 1.0

    const val TARGET_TOLERANCE = 1e-9

    const val CAMERA_PADDING_DP = 160.0

    const val REPLACEMENT_PADDING_DP = 80.0

    const val PAN_X_DP = 32.0

    const val PAN_Y_DP = -24.0

    const val WORLD_COPY_PAN_X_DP = -128.0

    const val WORLD_COPY_ANIMATION_MS = 200

    val SCREEN_CENTER = DpOffset(256.dp, 256.dp)

    val PADDED_CENTER = ScreenPoint(x = (512.0 + CAMERA_PADDING_DP) / 2.0, y = 256.0)

    val REPLACEMENT_PADDED_CENTER =
      ScreenPoint(x = (512.0 - REPLACEMENT_PADDING_DP) / 2.0, y = 256.0)

    val WIDE_EXTENT: MapExtent = MapExtent.fromLogical(width = 640, height = 512, scaleFactor = 1.0)

    val WIDE_SCREEN_CENTER = DpOffset(320.dp, 256.dp)

    val START_CAMERA = CameraPosition(target = Position(11.0, 47.0), zoom = 2.0)

    val ROTATED_CAMERA =
      CameraPosition(target = Position(11.0, 47.0), zoom = 5.0, bearing = 45.0, tilt = 40.0)

    val REPLACEMENT_CAMERA =
      CameraPosition(target = Position(12.0, 48.0), zoom = 4.0, bearing = 30.0, tilt = 35.0)

    val ANTIMERIDIAN_CAMERA =
      CameraPosition(target = Position(179.5, 20.0), zoom = 5.0, bearing = 20.0, tilt = 30.0)
  }
}
