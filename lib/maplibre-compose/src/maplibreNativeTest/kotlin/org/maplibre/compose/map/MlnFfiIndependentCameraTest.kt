package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.camera.CameraAnchor
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.systemAnimatorDurationScale
import org.maplibre.compose.testing.skipMapTest
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.Position

class MlnFfiIndependentCameraTest {
  @Test
  fun a_queued_camera_assignment_applies_before_a_following_partial_animation() = runBlocking {
    fixture().use { fixture ->
      val state = fixture.state
      val padding = DpPadding(bottom = 80.dp)
      state.setCameraPosition(state.cameraPosition.copy(padding = padding))
      fixture.awaitUntil("the initial padding") { state.cameraPosition.padding == padding }

      val ownerHeld = MlnFfiGate()
      assertTrue(fixture.session.postOwnerTaskForTest { ownerHeld.awaitUntilOpen() })
      val animation =
        try {
          state.setCameraPosition(
            state.cameraPosition.copy(target = Position(1.0, 1.0), padding = DpPadding.Zero)
          )
          async(start = CoroutineStart.UNDISPATCHED) {
            state.animateCamera(
              CameraUpdate(target = Position(2.0, 2.0)),
              CameraAnimation.Ease(100.milliseconds),
            )
          }
        } finally {
          ownerHeld.open()
        }

      fixture.awaitUntil("the partial animation to finish") { animation.isCompleted }
      animation.await()
      assertEquals(2.0, state.cameraPosition.target.longitude, 0.001)
      assertEquals(2.0, state.cameraPosition.target.latitude, 0.001)
      assertEquals(DpPadding.Zero, state.cameraPosition.padding)
    }
  }

  @Test
  fun a_flight_uses_the_camera_from_a_queued_assignment() = runBlocking {
    fixture().use { fixture ->
      val state = fixture.state
      val initial = state.cameraPosition
      val ownerHeld = MlnFfiGate()
      assertTrue(fixture.session.postOwnerTaskForTest { ownerHeld.awaitUntilOpen() })
      val flight =
        try {
          state.setCameraPosition(initial.copy(target = Position(120.0, 0.0)))
          async(start = CoroutineStart.UNDISPATCHED) {
            state.animateCamera(CameraUpdate(target = initial.target), CameraAnimation.Fly())
          }
        } finally {
          ownerHeld.open()
        }

      var lowestZoom = initial.zoom
      fixture.awaitUntil("the flight back to the initial target to finish") {
        lowestZoom = minOf(lowestZoom, state.cameraPosition.zoom)
        flight.isCompleted
      }
      flight.await()
      assertTrue(lowestZoom < initial.zoom - 0.5, "the flight did not zoom out: $lowestZoom")
      assertEquals(initial.zoom, state.cameraPosition.zoom, 0.001)
      assertEquals(initial.target.longitude, state.cameraPosition.target.longitude, 0.001)
      assertEquals(initial.target.latitude, state.cameraPosition.target.latitude, 0.001)
    }
  }

  @Test
  fun an_inset_change_queued_after_replacing_an_anchor_preserves_the_replacement() = runBlocking {
    fixture().use { fixture ->
      val state = fixture.state
      val anchored = async {
        state.animateCameraAround(
          CameraAnchor.Geographic(Position(0.0, 0.0)),
          zoom = 8.0,
          animation = CameraAnimation.Ease(3.seconds),
        )
      }
      fixture.awaitUntil("the anchor to start") { state.cameraPosition.zoom > 3.1 }
      // The owner thread holds still, so both commands enter the same owner batch, before another
      // native event drain.
      val ownerHeld = MlnFfiGate()
      fixture.session.postOwnerTaskForTest { ownerHeld.awaitUntilOpen() }
      val replacement =
        async(start = CoroutineStart.UNDISPATCHED) {
          state.animateCamera(
            CameraUpdate(target = Position(1.0, 1.0), zoom = 6.0),
            CameraAnimation.Ease(1.seconds),
          )
        }
      fixture.session.setViewportInsets(PaddingValues(top = 24.dp))
      ownerHeld.open()
      fixture.awaitUntil("the replacement to finish") { replacement.isCompleted }
      replacement.await()
      anchored.await()
      assertEquals(6.0, state.cameraPosition.zoom, 0.001)
    }
  }

  @Test
  fun disjoint_commands_finish_independently_and_cancellation_only_withdraws_the_waiter() =
    runBlocking {
      fixture().use { fixture ->
        val state = fixture.state
        val zoom = async {
          state.animateCamera(CameraUpdate(zoom = 8.0), CameraAnimation.Ease(3.seconds))
        }
        fixture.awaitUntil("zoom to start") { state.cameraPosition.zoom > 3.1 }
        val bearing = async {
          state.animateCamera(
            CameraUpdate(bearing = 90.0),
            CameraAnimation.Ease(300.milliseconds),
          )
        }
        fixture.awaitUntil("bearing to finish independently") { bearing.isCompleted }
        bearing.await()
        assertFalse(zoom.isCompleted)
        assertTrue(state.isCameraMoving)
        assertEquals(90.0, state.cameraPosition.bearing, 0.001)
        assertTrue(state.cameraPosition.zoom < 8.0)
        zoom.cancel()
        fixture.awaitUntil("cancelled waiter to unwind") { zoom.isCompleted }
        fixture.awaitUntil("the abandoned zoom to finish") { state.cameraPosition.zoom >= 7.999 }
        assertEquals(90.0, state.cameraPosition.bearing, 0.001)
        fixture.awaitUntil("all movement to end") { !state.isCameraMoving }
      }
    }

  @Test
  fun replacing_zoom_preserves_the_older_commands_bearing_and_completion() = runBlocking {
    fixture().use { fixture ->
      val state = fixture.state
      val original = async {
        state.animateCamera(
          CameraUpdate(zoom = 8.0, bearing = 90.0),
          CameraAnimation.Ease(2.seconds),
        )
      }
      fixture.awaitUntil("the original command to start") { state.cameraPosition.bearing > 1.0 }
      val replacement = async {
        state.animateCamera(CameraUpdate(zoom = 5.0), CameraAnimation.Ease(100.milliseconds))
      }
      fixture.awaitUntil("the replacement to finish") { replacement.isCompleted }
      replacement.await()
      assertFalse(original.isCompleted)
      assertTrue(state.isCameraMoving)
      assertTrue(state.cameraPosition.bearing < 90.0)
      assertEquals(5.0, state.cameraPosition.zoom, 0.001)
      fixture.awaitUntil("the original bearing to finish") { original.isCompleted }
      original.await()
      assertEquals(5.0, state.cameraPosition.zoom, 0.001)
      assertEquals(90.0, state.cameraPosition.bearing, 0.001)
    }
  }

  @Test
  fun replacing_a_coupled_target_stops_its_zoom_but_preserves_unrelated_bearing() = runBlocking {
    for (anchored in listOf(false, true)) {
      fixture().use { fixture ->
        val state = fixture.state
        val coupled = async {
          if (anchored)
            state.animateCameraAround(
              CameraAnchor.Geographic(Position(0.0, 0.0)),
              zoom = 8.0,
              animation = CameraAnimation.Ease(3.seconds),
            )
          else
            state.animateCamera(
              CameraUpdate(target = Position(10.0, 10.0), zoom = 8.0),
              CameraAnimation.Fly(3.seconds),
            )
        }
        fixture.awaitUntil("the coupled animation to start") {
          abs(state.cameraPosition.zoom - 3.0) > 0.1
        }
        val bearing = async {
          state.animateCamera(CameraUpdate(bearing = 90.0), CameraAnimation.Ease(1.seconds))
        }
        fixture.awaitUntil("independent bearing to start") { state.cameraPosition.bearing > 1.0 }
        assertFalse(coupled.isCompleted, "bearing must not replace the coupled target/zoom")
        val replacement = async {
          state.animateCamera(
            CameraUpdate(target = Position(1.0, 1.0)),
            CameraAnimation.Ease(0.milliseconds),
          )
        }
        fixture.awaitUntil("the coupled command to be superseded") {
          coupled.isCompleted && replacement.isCompleted
        }
        coupled.await()
        replacement.await()
        assertFalse(bearing.isCompleted)
        val stoppedZoom = state.cameraPosition.zoom
        fixture.awaitUntil("unrelated bearing to finish") { bearing.isCompleted }
        bearing.await()
        assertEquals(stoppedZoom, state.cameraPosition.zoom, 0.001)
        assertEquals(90.0, state.cameraPosition.bearing, 0.001)
      }
    }
  }

  private fun fixture(): BridgeMapFixture {
    if (systemAnimatorDurationScale() == 0f) skipMapTest("System animations are disabled")
    return BridgeMapFixture.create().also {
      it.state.publishPresentation(it.state.reservePresentation(), it.session)
      it.bindState(it.state)
      it.loadStyle(BaseStyle.Empty)
      it.state.setCameraPosition(CameraPosition(zoom = 3.0))
      it.pumpUntil("the initial camera") { it.state.cameraPosition.zoom == 3.0 }
    }
  }
}
