package org.maplibre.compose.map

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Position

/**
 * Coordinate conversion uses the snapshot's frozen projection, including under pitch and bearing,
 * so overlays land where the map draws them.
 */
class MlnFfiProjectionTest {

  @Test
  fun presented_projection_keeps_the_rendered_camera_and_tracks_texture_geometry() = runMapTest {
    BridgeMapFixture.create(initialExtent = MapExtent.fromLogical(200, 200, 1.0)).use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val rendered = fixture.renderedFrames
      fixture.session.setCameraPosition(RotatedCamera)
      fixture.awaitUntil("the rotated camera to apply") {
        abs(fixture.session.getCameraPosition().bearing - RotatedCamera.bearing) < 0.01
      }
      fixture.awaitUntil("the rotated camera to render") { fixture.renderedFrames > rendered }
      val oldSnapshot =
        fixture.renderFrameProjection().use { projection ->
          fixture.session.presentFrame(projection, MlnFfiMapDestination(0, 0, 200, 200), 1.0)
          val initial =
            assertNotNull(fixture.session.overlayScreenLocationFromPosition(RotatedCamera.center))
          assertTrue(initial.isNear(DpOffset(100.dp, 100.dp)))

          val movedCamera = StartCamera.copy(center = Position(25.0, 40.0))
          fixture.session.setCameraPosition(movedCamera)
          fixture.awaitUntil("the live camera to advance") {
            abs(fixture.session.getCameraPosition().bearing - StartCamera.bearing) < 0.01
          }
          assertEquals(
            initial,
            fixture.session.overlayScreenLocationFromPosition(RotatedCamera.center),
          )
          assertTrue(
            !fixture.session.screenLocationFromPosition(RotatedCamera.center).isNear(initial),
          )

          // A retained 200px texture centered in a 300px surface at density 2.
          fixture.session.presentFrame(projection, MlnFfiMapDestination(50, 50, 200, 200), 2.0)
          val expected = DpOffset(75.dp, 75.dp)
          assertTrue(
            fixture.session
              .overlayScreenLocationFromPosition(RotatedCamera.center)
              .isNear(expected),
          )
          assertTrue(fixture.session.screenLocationFromPosition(movedCamera.center).isNear(initial))
          Snapshot.takeSnapshot().also {
            fixture.session.presentFrame(null, MlnFfiMapDestination(0, 0, 0, 0), 1.0)
          }
        }
      try {
        // A Compose snapshot can outlive the frame whose handle has just been closed.
        oldSnapshot.enter {
          assertNotNull(fixture.session.overlayScreenLocationFromPosition(StartCamera.center))
        }
      } finally {
        oldSnapshot.dispose()
      }
    }
  }

  @Test
  fun a_snapshot_projection_round_trips_under_pitch_and_bearing() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.session.setCameraPosition(RotatedCamera)
      fixture.awaitUntil("the camera target to land on the screen center") {
        val camera = fixture.session.getCameraPosition()
        val projected = fixture.session.screenLocationFromPosition(camera.center)
        abs(camera.bearing - RotatedCamera.bearing) < 0.01 &&
          abs(camera.zoom - RotatedCamera.zoom) < 0.01 &&
          abs(camera.pitch - RotatedCamera.pitch) < 0.01 &&
          projected.isNear(ScreenCenter)
      }

      // Both conversions use the same snapshot projection.
      val camera = fixture.session.getCameraPosition()
      val projected = fixture.session.screenLocationFromPosition(camera.center)
      assertTrue(
        projected.isNear(ScreenCenter),
        "the camera target ${camera.center} should project to $ScreenCenter ± $PixelTolerance, was $projected",
      )

      val roundTrip =
        fixture.session.screenLocationFromPosition(
          assertNotNull(fixture.session.positionFromScreenLocation(ScreenCenter)),
        )
      assertTrue(
        roundTrip.isNear(ScreenCenter),
        "the screen center $ScreenCenter should round-trip, was $roundTrip",
      )
    }
  }

  @Test
  fun consecutive_one_pixel_resizes_keep_the_camera_target() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.session.setCameraPosition(StartCamera)
      fixture.awaitUntil("the starting camera to apply") {
        abs(fixture.session.getCameraPosition().zoom - StartCamera.zoom) < 0.01 &&
          fixture.session.screenLocationFromPosition(StartCamera.center).isNear(ScreenCenter)
      }

      val start = fixture.session.getCameraPosition().center
      for (width in 200..210) {
        val extent = MapExtent.fromLogical(width, 200, scaleFactor = 1.0)
        fixture.extent = extent
        fixture.awaitUntil("the viewport to report ${extent.width}x${extent.height}") {
          fixture.session.getViewport()?.size == DpSize(width.dp, 200.dp)
        }
        val camera = fixture.session.getCameraPosition()
        assertTrue(
          abs(camera.center.latitude - start.latitude) < TargetTolerance &&
            abs(camera.center.longitude - start.longitude) < TargetTolerance,
          "resize to ${extent.width}x${extent.height} moved the camera from $start to ${camera.center}",
        )
        val projected = fixture.session.screenLocationFromPosition(start)
        val expectedCenter = DpOffset((width / 2.0).dp, 100.dp)
        assertTrue(
          projected.isNear(expectedCenter),
          "the camera target should stay at the visual center $expectedCenter ± $PixelTolerance, was $projected",
        )
      }
    }
  }

  @Test
  fun a_resize_reprojects_the_camera_target_to_the_new_center() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.session.setCameraPosition(StartCamera)
      fixture.awaitUntil("the camera target to land on the first screen center") {
        val projected = fixture.session.screenLocationFromPosition(StartCamera.center)
        abs(fixture.session.getCameraPosition().zoom - StartCamera.zoom) < 0.01 &&
          projected.isNear(ScreenCenter)
      }

      val movedBefore = fixture.events.count { it == "viewportChanged" }
      fixture.extent = WideExtent
      fixture.awaitUntil("the viewport to report the resized map") {
        fixture.session.getViewport()?.size == DpSize(640.dp, 512.dp)
      }
      fixture.awaitUntil("the camera target to land on the resized screen center") {
        fixture.session.screenLocationFromPosition(StartCamera.center).isNear(WideScreenCenter)
      }

      assertTrue(
        fixture.events.count { it == "viewportChanged" } > movedBefore,
        "a resize should report viewportChanged so Compose overlays re-read the projection",
      )
    }
  }

  private fun DpOffset?.isNear(other: DpOffset): Boolean =
    this != null &&
      abs(x.value - other.x.value) <= PixelTolerance &&
      abs(y.value - other.y.value) <= PixelTolerance

  private companion object {
    const val PixelTolerance = 1.0

    const val TargetTolerance = 1e-9

    val ScreenCenter = DpOffset(256.dp, 256.dp)

    val WideExtent: MapExtent = MapExtent.fromLogical(width = 640, height = 512, scaleFactor = 1.0)

    val WideScreenCenter = DpOffset(320.dp, 256.dp)

    val StartCamera = CameraPosition(center = Position(11.0, 47.0), zoom = 2.0)

    val RotatedCamera =
      CameraPosition(center = Position(11.0, 47.0), zoom = 5.0, bearing = 45.0, pitch = 40.0)
  }
}
