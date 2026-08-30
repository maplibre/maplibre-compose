package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class CameraMoveReportingTest {

  @Test
  fun a_drag_reports_one_move_spanning_the_whole_gesture(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtRest()

      val token = it.gestures.onGestureStarted()
      repeat(DRAG_SAMPLES) { sample ->
        it.gestures.moveBy(DRAG_STEP_DP, DRAG_STEP_DP, gestureToken = token)
        it.pump(FRAMES_PER_SAMPLE)
        assertTrue(it.presentation.isCameraMoving, "the drag ended at sample $sample")
        assertEquals(CameraMoveReason.GESTURE, it.presentation.cameraMoveReason)
      }
      it.settle()
      it.gestures.onGestureEnded(token)
      it.pump(FRAMES_PER_SAMPLE)

      assertFalse(it.presentation.isCameraMoving)
      assertEquals(CameraMoveReason.GESTURE, it.presentation.cameraMoveReason)
    }
  }

  @Test
  fun a_programmatic_move_reports_a_complete_move_on_its_own(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtRest()

      it.gestures.moveBy(DRAG_STEP_DP, DRAG_STEP_DP)
      it.pump(FRAMES_PER_SAMPLE)

      assertFalse(it.presentation.isCameraMoving)
      assertEquals(CameraMoveReason.PROGRAMMATIC, it.presentation.cameraMoveReason)
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
  }
}
