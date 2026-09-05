package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Position

class BoxZoomIntegrationTest {
  @Test
  fun box_fit_preserves_orientation_and_padding_under_its_gesture_session(): MapTestResult =
    runMapTest {
      coroutineScope {
        createMapFixture().use { fixture ->
          fixture.loadStyle(BaseStyle.Empty)
          fixture.session.setCameraPadding(
            PaddingValues(start = 50.dp, top = 20.dp, end = 10.dp, bottom = 30.dp)
          )
          val initial =
            CameraPosition(target = Position(179.0, 0.0), zoom = 4.0, bearing = 25.0, tilt = 20.0)
          fixture.state.setCameraPosition(initial)
          fixture.awaitMapReady()
          fixture.settle()
          val size = assertNotNull(fixture.state.viewport).size
          val fit =
            assertNotNull(
              fixture.gestures.boxZoomFit(
                DpRect(
                  size.width / 2 - 40.dp,
                  size.height / 2 - 40.dp,
                  size.width / 2 + 40.dp,
                  size.height / 2 + 40.dp,
                )
              )
            )
          val input = GestureInputSession(this, fixture.gestures)
          try {
            fixture.awaitWhileRendering("box fit completes") {
              fixture.gestures.fitBoundsAwaitingTransition(fit, 100.milliseconds, input.token)
              input.end()
              fixture.gestures.awaitGestureEnded(input.token)
            }
            fixture.settle()
            val actual = fixture.state.cameraPosition
            assertTrue(actual.zoom > initial.zoom)
            assertEquals(25.0, actual.bearing, 1e-5)
            assertEquals(20.0, actual.tilt, 1e-5)
            assertEquals(CameraMoveReason.GESTURE, fixture.state.cameraMoveReason)
            assertFalse(fixture.state.isCameraMoving)

            // The existing fit API defines padding and pitched-fit semantics for both engines.
            fixture.state.setCameraPosition(initial)
            fixture.settle()
            fixture.state.fitCameraToBounds(fit.bounds, fit.bearing, fit.tilt, PaddingValues())
            fixture.settle()
            val expected = fixture.state.cameraPosition
            assertEquals(expected.zoom, actual.zoom, 1e-5)
            assertEquals(expected.target.longitude, actual.target.longitude, 1e-5)
            assertEquals(expected.target.latitude, actual.target.latitude, 1e-5)
          } finally {
            input.cancel()
          }
        }
      }
    }

  @Test
  fun public_camera_takeover_cancels_an_in_flight_box_fit(): MapTestResult = runMapTest {
    coroutineScope {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        fixture.state.setCameraPosition(CameraPosition(zoom = 3.0))
        fixture.awaitMapReady()
        fixture.settle()
        val size = assertNotNull(fixture.state.viewport).size
        val fit =
          assertNotNull(
            fixture.gestures.boxZoomFit(
              DpRect(size.width / 4, size.height / 4, size.width * 3 / 4, size.height * 3 / 4)
            )
          )
        val input = GestureInputSession(this, fixture.gestures)
        try {
          val motion =
            input.scope.launch {
              fixture.gestures.fitBoundsAwaitingTransition(fit, 2.seconds, input.token)
              input.end()
            }
          fixture.pumpUntil("box fit changes the camera") {
            fixture.state.cameraPosition.zoom > 3.01
          }
          val replacement = CameraPosition(target = Position(25.0, 10.0), zoom = 7.0)
          fixture.state.setCameraPosition(replacement)
          fixture.awaitWhileRendering("box fit cancels") { motion.join() }
          fixture.settle()
          assertTrue(motion.isCancelled)
          assertEquals(replacement.zoom, fixture.state.cameraPosition.zoom, 1e-5)
          assertEquals(
            replacement.target.longitude,
            fixture.state.cameraPosition.target.longitude,
            1e-5,
          )
          assertEquals(CameraMoveReason.PROGRAMMATIC, fixture.state.cameraMoveReason)
        } finally {
          input.cancel()
        }
      }
    }
  }
}
