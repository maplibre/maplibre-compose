package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * Both backends advance a transition only from inside a render, so every test renders as it waits.
 */
class MapCameraTransitionTest {

  @Test
  fun an_animation_requested_before_the_first_frame_reaches_its_target(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.session.setBaseStyle(BaseStyle.Empty, 1L)
        val animation =
          CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            it.session.animateCameraPosition(TARGET, 200.milliseconds)
          }

        assertFalse(
          animation.isCompleted,
          "the animation should wait until the map can run it",
        )
        it.pumpUntil("the startup animation to complete") { animation.isCompleted }

        assertFalse(animation.isCancelled, "the startup animation should complete normally")
        assertNear(
          TARGET.zoom,
          it.session.getCameraPosition().zoom,
          "the startup animation should reach its target zoom",
        )
        assertNear(
          TARGET.target.longitude,
          it.session.getCameraPosition().target.longitude,
          "the startup animation should reach its target longitude",
        )
        assertNear(
          TARGET.target.latitude,
          it.session.getCameraPosition().target.latitude,
          "the startup animation should reach its target latitude",
        )
      }
    }

  @Test
  fun closing_before_the_first_frame_resumes_a_queued_animation(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.session.setBaseStyle(BaseStyle.Empty, 1L)
      val animation =
        CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
          it.session.animateCameraPosition(TARGET, 60.seconds)
        }

      assertFalse(
        animation.isCompleted,
        "the animation should still be waiting when the session closes",
      )
      it.closeSession()
      it.pumpUntil("the queued animation to resume during teardown") { animation.isCompleted }

      assertFalse(animation.isCancelled, "teardown should resume the waiter, not cancel it")
    }
  }

  @Test
  fun a_bounds_fit_requested_before_the_first_frame_uses_the_real_viewport(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        // A camera read makes mln-ffi's map creation deterministic without a render target.
        it.session.getCameraPosition()
        // The fit suspends until the map applies it, so it runs beside the frame pump.
        val deferredFitCall =
          CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            it.session.setCameraPosition(
              BOUNDS,
              bearing = 0.0,
              tilt = 0.0,
              padding = PaddingValues(0.dp),
            )
          }
        it.session.getCameraPosition()

        it.awaitMapReady()
        it.pumpUntil("the deferred bounds fit to be applied") {
          deferredFitCall.isCompleted && it.session.getCameraPosition().zoom > 1.0
        }
        val deferredFit = it.session.getCameraPosition()

        it.session.setCameraPosition(START)
        it.pumpUntil("the camera to reset") {
          abs(it.session.getCameraPosition().zoom - START.zoom) < 0.01
        }
        val attachedFitCall =
          CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            it.session.setCameraPosition(
              BOUNDS,
              bearing = 0.0,
              tilt = 0.0,
              padding = PaddingValues(0.dp),
            )
          }
        it.pumpUntil("the attached bounds fit to be applied") {
          attachedFitCall.isCompleted && abs(it.session.getCameraPosition().zoom - START.zoom) > 0.1
        }
        val attachedFit = it.session.getCameraPosition()

        assertNear(attachedFit.zoom, deferredFit.zoom, "the first fit used the startup viewport")
        assertNear(
          attachedFit.target.longitude,
          deferredFit.target.longitude,
          "the first fit chose the wrong longitude",
        )
        assertNear(
          attachedFit.target.latitude,
          deferredFit.target.latitude,
          "the first fit chose the wrong latitude",
        )
      }
    }

  @Test
  fun a_bounds_jump_adds_transient_fit_padding_to_camera_padding(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Empty)
      it.session.setCameraPadding(CAMERA_PADDING)
      it.session.setCameraPosition(START)
      it.awaitMapReady()
      it.pumpUntil("the camera padding to be applied") {
        it.cameraTargetMatches(START, CAMERA_PADDING)
      }

      it.session.setCameraPosition(BOUNDS, 0.0, 0.0, FIT_PADDING)
      it.pumpUntil("the bounds fit to be applied") {
        abs(it.session.getCameraPosition().zoom - START.zoom) > 0.1
      }
      val fitAfterPadding = it.session.getCameraPosition()
      it.assertCameraTarget(fitAfterPadding, CAMERA_PADDING)
      it.assertBoundsInside(CAMERA_PADDING + FIT_PADDING)

      it.session.setCameraPosition(BOUNDS, 0.0, 0.0, FIT_PADDING)
      it.pump(frames = 2)
      val repeatedFit = it.session.getCameraPosition()
      assertSameFit(fitAfterPadding, repeatedFit, "repeating the bounds fit changed its camera")

      it.session.setCameraPadding(REPLACEMENT_CAMERA_PADDING)
      it.pumpUntil("the replacement camera padding to be applied") {
        it.cameraTargetMatches(fitAfterPadding, REPLACEMENT_CAMERA_PADDING)
      }
    }
  }

  @Test
  fun a_bounds_fit_crosses_the_antimeridian_the_short_way(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()

      it.session.setCameraPosition(ANTIMERIDIAN_BOUNDS, 0.0, 0.0, PaddingValues(0.dp))
      it.pumpUntil("the antimeridian bounds fit to be applied") {
        val camera = it.session.getCameraPosition()
        abs(abs(camera.target.longitude) - 180.0) < 1.0 && camera.zoom > START.zoom
      }
    }
  }

  @Test
  fun a_bounds_animation_keeps_fit_padding_transient(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Empty)
      it.session.setCameraPadding(CAMERA_PADDING)
      it.session.setCameraPosition(START)
      it.awaitMapReady()
      it.pumpUntil("the camera padding to be applied") {
        it.cameraTargetMatches(START, CAMERA_PADDING)
      }

      it.awaitWhileRendering("the bounds animation to complete") {
        it.session.animateCameraPosition(BOUNDS, 0.0, 0.0, FIT_PADDING, 200.milliseconds)
      }

      val firstFit = it.session.getCameraPosition()
      it.assertCameraTarget(firstFit, CAMERA_PADDING)
      it.assertBoundsInside(CAMERA_PADDING + FIT_PADDING)

      it.awaitWhileRendering("the repeated bounds animation to complete") {
        it.session.animateCameraPosition(BOUNDS, 0.0, 0.0, FIT_PADDING, 200.milliseconds)
      }

      assertSameFit(
        firstFit,
        it.session.getCameraPosition(),
        "repeating the bounds animation changed its camera",
      )
    }
  }

  @Test
  fun an_animation_completes_and_lands_on_its_target(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()

      it.awaitWhileRendering("the animation to complete") {
        it.session.animateCameraPosition(TARGET, 200.milliseconds)
      }

      assertNear(
        TARGET.zoom,
        it.session.getCameraPosition().zoom,
        "the camera should have reached the target zoom",
      )
    }
  }

  @Test
  fun reapplying_identical_camera_constraints_does_not_cancel_an_animation(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.startAtOrigin()
        it.session.applyTestConstraints()
        it.pump(frames = 2)

        val animation =
          CoroutineScope(Dispatchers.Default).launch {
            it.session.animateCameraPosition(TARGET, 2.seconds)
          }
        it.awaitCameraMoving()
        it.session.applyTestConstraints()
        it.pumpUntil("the animation to complete after the constraints repeat") {
          animation.isCompleted
        }

        assertNear(
          TARGET.zoom,
          it.session.getCameraPosition().zoom,
          "repeating identical constraints should not stop the animation",
        )
      }
    }

  @Test
  fun replacing_a_camera_range_with_a_disjoint_range_applies_it_atomically(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.startAtOrigin()
        it.session.setCameraConstraints(TEST_CONSTRAINTS)
        it.pump(frames = 2)

        it.session.setCameraConstraints(DISJOINT_ZOOM_CONSTRAINTS)
        it.pumpUntil("the camera to adopt the disjoint zoom range") {
          abs(it.session.getCameraPosition().zoom - DISJOINT_ZOOM_CONSTRAINTS.minZoom) < 0.01
        }
      }
    }

  /** A zero-duration animation emits its event during the call, so it must not deadlock. */
  @Test
  fun a_zero_duration_animation_completes(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()

      it.awaitWhileRendering("the instant animation to complete") {
        it.session.animateCameraPosition(TARGET, 0.milliseconds)
      }
    }
  }

  /**
   * Replacing a transition ends the old one, and that end belongs to the transition it replaced.
   */
  @Test
  fun a_replacement_animation_waits_for_its_own_end(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()

      val superseded =
        CoroutineScope(Dispatchers.Default).launch {
          it.session.animateCameraPosition(TARGET, 10.seconds)
        }
      it.awaitCameraMoving()

      val replacement =
        CoroutineScope(Dispatchers.Default).launch {
          it.session.animateCameraPosition(MIDPOINT, 2.seconds)
        }
      it.pumpUntil("the superseded animation to resume") { superseded.isCompleted }

      assertFalse(
        replacement.isCompleted,
        "the replacement should still be running when the animation it replaced ends",
      )

      it.pumpUntil("the replacement animation to complete") { replacement.isCompleted }
      assertNear(
        MIDPOINT.zoom,
        it.session.getCameraPosition().zoom,
        "the replacement should have reached its own target",
      )
    }
  }

  @Test
  fun a_superseded_animation_resumes_rather_than_hanging(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()

      val animation: Job =
        CoroutineScope(Dispatchers.Default).launch {
          it.session.animateCameraPosition(TARGET, 10.seconds)
        }
      it.awaitCameraMoving()
      it.session.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 4.0))

      it.pumpUntil("the superseded animation to resume") { animation.isCompleted }
      assertFalse(animation.isCancelled, "a superseded animation should resume, not cancel")
    }
  }

  @Test
  fun cancelling_an_animation_stops_the_camera_and_leaves_nothing_registered(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.startAtOrigin()
        it.events.clear()

        val animation =
          CoroutineScope(Dispatchers.Default).launch {
            it.session.animateCameraPosition(TARGET, 30.seconds)
          }
        it.awaitCameraMoving()
        animation.cancel()
        it.pumpUntil("the cancelled animation to unwind") { animation.isCompleted }

        val stopped = it.session.getCameraPosition()
        assertTrue(
          stopped.zoom < TARGET.zoom - 0.1,
          "the camera should have stopped short of the target, but was $stopped",
        )

        it.awaitWhileRendering("a later animation to complete") {
          it.session.animateCameraPosition(TARGET, 200.milliseconds)
        }
        assertNear(
          TARGET.zoom,
          it.session.getCameraPosition().zoom,
          "a later animation should still complete",
        )
      }
    }

  @Test
  fun closing_the_session_resumes_an_outstanding_animation(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()

      val animation =
        CoroutineScope(Dispatchers.Default).launch {
          it.session.animateCameraPosition(TARGET, 60.seconds)
        }
      it.awaitCameraMoving()
      it.pumpUntil("the animation's camera move to be reported") {
        it.events.count { event -> event.startsWith("cameraMoveStarted") } >
          it.events.count { event -> event == "cameraMoveEnded" }
      }
      val endedBeforeClose = it.events.count { event -> event == "cameraMoveEnded" }

      it.closeSession()

      it.pumpUntil("the stranded animation to resume") { animation.isCompleted }
      assertFalse(animation.isCancelled, "teardown should resume the waiter, not cancel it")
      assertEquals(
        endedBeforeClose + 1,
        it.events.count { event -> event == "cameraMoveEnded" },
        "teardown should close the outstanding camera move exactly once: ${it.events}",
      )
    }
  }

  private suspend fun MapFixture.startAtOrigin() {
    // GL JS renders nothing without a style.
    loadStyle(BaseStyle.Empty)
    session.setCameraPosition(START)
    // Render first: before the map exists, a camera read echoes back whatever was last set.
    awaitMapReady()
    pumpUntil("the map to reach its starting camera") {
      abs(session.getCameraPosition().zoom - START.zoom) < 0.001
    }
  }

  private suspend fun MapFixture.awaitCameraMoving() {
    pumpUntil("the animation to start moving the camera") {
      abs(session.getCameraPosition().zoom - START.zoom) > 0.01
    }
  }

  private fun MapAdapter.applyTestConstraints() {
    setCameraConstraints(TEST_CONSTRAINTS)
  }

  private companion object {
    val START = CameraPosition(target = Position(0.0, 0.0), zoom = 2.0)
    val TARGET = CameraPosition(target = Position(11.0, 47.0), zoom = 8.0)
    val MIDPOINT = CameraPosition(target = Position(5.0, 20.0), zoom = 5.0)
    val BOUNDS =
      BoundingBox(
        southwest = Position(longitude = -5.0, latitude = -5.0),
        northeast = Position(longitude = 5.0, latitude = 5.0),
      )
    val TEST_CONSTRAINTS =
      CameraConstraints(
        minZoom = 0.0,
        maxZoom = 20.0,
        minPitch = 0.0,
        maxPitch = 60.0,
        boundingBox = null,
      )
    val DISJOINT_ZOOM_CONSTRAINTS = TEST_CONSTRAINTS.copy(minZoom = 21.0, maxZoom = 22.0)
    val ANTIMERIDIAN_BOUNDS =
      BoundingBox(
        southwest = Position(longitude = 170.0, latitude = -10.0),
        northeast = Position(longitude = -170.0, latitude = 10.0),
      )
    val CAMERA_PADDING =
      PaddingValues.Absolute(left = 120.dp, top = 10.dp, right = 5.dp, bottom = 30.dp)
    val FIT_PADDING =
      PaddingValues.Absolute(left = 40.dp, top = 20.dp, right = 70.dp, bottom = 60.dp)
    val REPLACEMENT_CAMERA_PADDING =
      PaddingValues.Absolute(left = 15.dp, top = 35.dp, right = 80.dp, bottom = 5.dp)

    operator fun PaddingValues.plus(other: PaddingValues): PaddingValues =
      PaddingValues.Absolute(
        left = left() + other.left(),
        top = calculateTopPadding() + other.calculateTopPadding(),
        right = right() + other.right(),
        bottom = calculateBottomPadding() + other.calculateBottomPadding(),
      )

    fun PaddingValues.left() = calculateLeftPadding(LayoutDirection.Ltr)

    fun PaddingValues.right() = calculateRightPadding(LayoutDirection.Ltr)

    fun MapFixture.cameraTargetMatches(
      position: CameraPosition,
      padding: PaddingValues,
    ): Boolean {
      val viewport = session.getViewport() ?: return false
      val target = session.screenLocationFromPosition(position.target) ?: return false
      val expectedX = (viewport.size.width + padding.left() - padding.right()) / 2
      val expectedY =
        (viewport.size.height + padding.calculateTopPadding() - padding.calculateBottomPadding()) /
          2
      return abs(target.x.value - expectedX.value) < 0.01 &&
        abs(target.y.value - expectedY.value) < 0.01
    }

    fun MapFixture.assertCameraTarget(position: CameraPosition, padding: PaddingValues) {
      assertTrue(
        cameraTargetMatches(position, padding),
        "the camera target does not use the persistent camera padding",
      )
    }

    fun MapFixture.assertBoundsInside(padding: PaddingValues) {
      val viewport = requireNotNull(session.getViewport())
      val southwest = requireNotNull(session.screenLocationFromPosition(BOUNDS.southwest))
      val northeast = requireNotNull(session.screenLocationFromPosition(BOUNDS.northeast))
      val tolerance = 1.0
      assertTrue(southwest.x.value + tolerance >= padding.left().value)
      assertTrue(
        southwest.y.value - tolerance <=
          viewport.size.height.value - padding.calculateBottomPadding().value
      )
      assertTrue(northeast.x.value - tolerance <= viewport.size.width.value - padding.right().value)
      assertTrue(northeast.y.value + tolerance >= padding.calculateTopPadding().value)
    }

    fun assertNear(expected: Double, actual: Double, message: String) {
      assertTrue(abs(expected - actual) < 0.01, "$message (expected $expected, was $actual)")
    }

    fun assertSameFit(expected: CameraPosition, actual: CameraPosition, message: String) {
      assertNear(expected.zoom, actual.zoom, "$message zoom")
      assertNear(expected.target.longitude, actual.target.longitude, "$message longitude")
      assertNear(expected.target.latitude, actual.target.latitude, "$message latitude")
    }
  }
}
