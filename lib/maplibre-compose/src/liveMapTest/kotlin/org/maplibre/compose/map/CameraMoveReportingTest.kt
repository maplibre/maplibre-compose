package org.maplibre.compose.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Position

class CameraMoveReportingTest {

  @Test
  fun a_drag_reports_one_move_spanning_the_whole_gesture(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtRest()

      val token = it.gestures.onGestureStarted()
      repeat(DRAG_SAMPLES) { sample ->
        it.gestures.moveBy(DRAG_STEP_DP, DRAG_STEP_DP, gestureToken = token)
        it.pump(FRAMES_PER_SAMPLE)
        assertTrue(it.state.isCameraMoving, "the drag ended at sample $sample")
        assertEquals(CameraMoveReason.GESTURE, it.state.cameraMoveReason)
      }
      it.settle()
      it.gestures.onGestureEnded(token)
      it.pump(FRAMES_PER_SAMPLE)

      assertFalse(it.state.isCameraMoving)
      assertEquals(CameraMoveReason.GESTURE, it.state.cameraMoveReason)
    }
  }

  @Test
  fun a_programmatic_move_reports_a_complete_move_on_its_own(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtRest()

      it.gestures.moveBy(DRAG_STEP_DP, DRAG_STEP_DP)
      it.pump(FRAMES_PER_SAMPLE)

      assertFalse(it.state.isCameraMoving)
      assertEquals(CameraMoveReason.PROGRAMMATIC, it.state.cameraMoveReason)
    }
  }

  @Test
  fun a_scale_changes_the_native_zoom(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtRest()
      it.state.setCameraPosition(START)
      it.pumpUntil("the camera to adopt the start zoom") {
        abs(it.session.getCameraPosition().zoom - START.zoom) < ZOOM_TOLERANCE
      }

      it.gestures.scaleBy(2.0, anchor = null)
      it.pumpUntil("the native camera to scale") {
        abs(it.session.getCameraPosition().zoom - (START.zoom + 1.0)) < ZOOM_TOLERANCE
      }
    }
  }

  @Test
  fun a_rotate_and_pitch_changes_the_native_camera(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtRest()
      it.state.setCameraPosition(START)
      it.pumpUntil("the camera to adopt the start pose") {
        abs(it.session.getCameraPosition().zoom - START.zoom) < ZOOM_TOLERANCE
      }

      it.gestures.rotateAndPitchBy(30.0, 15.0)
      it.pumpUntil("the native camera to rotate and tilt") {
        val camera = it.session.getCameraPosition()
        abs(camera.bearing - 30.0) < ANGLE_TOLERANCE && abs(camera.tilt - 15.0) < ANGLE_TOLERANCE
      }
    }
  }

  private suspend fun MapFixture.startAtRest() {
    loadStyle(BaseStyle.Empty)
    awaitMapReady()
    settle()
  }

  private companion object {
    const val DRAG_SAMPLES = 4

    const val DRAG_STEP_DP = 10.0

    const val FRAMES_PER_SAMPLE = 4

    const val ZOOM_TOLERANCE = 0.05

    const val ANGLE_TOLERANCE = 0.5

    val START = CameraPosition(target = Position(0.0, 0.0), zoom = 4.0)
  }
}
