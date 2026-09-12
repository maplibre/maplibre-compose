package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.systemAnimatorDurationScale
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.testing.skipMapTest
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * Both backends advance a transition only from inside a render, so every test renders as it waits.
 */
class MapCameraTransitionTest {

  @Test
  fun a_bounds_query_can_be_applied_with_transient_padding(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Empty)
      it.session.setCameraPadding(CAMERA_PADDING)
      it.state.setCameraPosition(START)
      it.awaitMapReady()
      it.pumpUntil("the camera padding to be applied") {
        it.cameraTargetMatches(START, CAMERA_PADDING)
      }
      val before = it.session.getCameraPosition()
      val camera = it.state.cameraForBounds(BOUNDS, padding = FIT_PADDING)
      it.pump(frames = 2)
      assertSameFit(before, it.session.getCameraPosition(), "the query moved the camera")

      it.state.setCameraPosition(camera)
      it.pumpUntil("the calculated camera to be applied") {
        abs(it.session.getCameraPosition().zoom - camera.zoom) < 0.01
      }
      it.assertCameraTarget(camera, CAMERA_PADDING)
      it.assertBoundsInside(CAMERA_PADDING + FIT_PADDING)

      it.state.fitCameraToBounds(BOUNDS, padding = FIT_PADDING)
      it.pump(frames = 2)
      assertSameFit(camera, it.session.getCameraPosition(), "the query disagrees with the fit")
    }
  }

  @Test
  fun a_bounds_query_waits_for_the_first_viewport(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val query =
        async(start = CoroutineStart.UNDISPATCHED) {
          fixture.state.cameraForBounds(ANTIMERIDIAN_BOUNDS)
        }
      assertFalse(query.isCompleted)
      fixture.awaitMapReady()
      val camera = withTimeout(30.seconds) { query.await() }
      assertTrue(abs(abs(camera.target.longitude) - 180.0) < 1.0)
      assertTrue(camera.zoom > START.zoom)
    }
  }

  @Test
  fun a_bounds_query_does_not_interrupt_an_animation(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()
      val animation =
        launch(Dispatchers.Default) {
          it.state.animateCameraPosition(TARGET, 2.seconds)
        }
      it.awaitCameraMoving()
      it.state.cameraForBounds(BOUNDS, bearing = 35.0, tilt = 20.0).also { camera ->
        assertNear(35.0, camera.bearing, "the query bearing")
        assertNear(20.0, camera.tilt, "the query tilt")
      }
      it.pumpUntil("the animation to complete after the query") { animation.isCompleted }
      assertFalse(animation.isCancelled)
      assertNear(TARGET.zoom, it.session.getCameraPosition().zoom, "the animation target")
    }
  }

  @Test
  fun a_geometry_query_matches_the_bounds_query_for_the_box_corners(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()
      val corners =
        Polygon(listOf(listOf(BOUNDS_NW, BOUNDS.northeast, BOUNDS_SE, BOUNDS.southwest, BOUNDS_NW)))
      val fromBounds = it.state.cameraForBounds(BOUNDS, bearing = 35.0, padding = FIT_PADDING)
      val fromGeometry = it.state.cameraForGeometry(corners, bearing = 35.0, padding = FIT_PADDING)
      assertSameFit(fromBounds, fromGeometry, "the geometry query disagrees with the bounds query")
      assertNear(35.0, fromGeometry.bearing, "the query bearing")
    }
  }

  /**
   * At bearing 45, the box's corners rotate onto the axes while the diamond's vertices leave them.
   */
  @Test
  fun a_geometry_query_fits_rotated_positions_tighter_than_their_bounds(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.startAtOrigin()
        val fromBounds = it.state.cameraForBounds(BOUNDS, bearing = 45.0, padding = FIT_PADDING)
        val camera =
          it.state.cameraForCoordinates(DIAMOND_ROUTE, bearing = 45.0, padding = FIT_PADDING)
        assertTrue(
          camera.zoom > fromBounds.zoom + 0.5,
          "the diamond fit should zoom in past the bounds fit (${camera.zoom} vs ${fromBounds.zoom})",
        )

        it.state.setCameraPosition(camera)
        it.pumpUntil("the calculated camera to be applied") {
          abs(it.session.getCameraPosition().zoom - camera.zoom) < 0.01
        }
        it.assertPositionsInside(DIAMOND_ROUTE, FIT_PADDING)
      }
    }

  @Test
  fun a_coordinates_query_crosses_the_antimeridian_with_continuous_longitudes(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.startAtOrigin()
        val camera = it.state.cameraForCoordinates(ANTIMERIDIAN_ROUTE)
        assertTrue(
          abs(abs(camera.target.longitude) - 180.0) < 1.0,
          "the target should sit on the antimeridian, but was ${camera.target}",
        )
        assertTrue(camera.zoom > START.zoom)
      }
    }

  @Test
  fun a_bounds_jump_adds_transient_fit_padding_to_camera_padding(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Empty)
      it.session.setCameraPadding(CAMERA_PADDING)
      it.state.setCameraPosition(START)
      it.awaitMapReady()
      it.pumpUntil("the camera padding to be applied") {
        it.cameraTargetMatches(START, CAMERA_PADDING)
      }

      it.state.fitCameraToBounds(BOUNDS, 0.0, 0.0, FIT_PADDING)
      it.pumpUntil("the bounds fit to be applied") {
        abs(it.session.getCameraPosition().zoom - START.zoom) > 0.1
      }
      val fitAfterPadding = it.session.getCameraPosition()
      it.assertCameraTarget(fitAfterPadding, CAMERA_PADDING)
      it.assertBoundsInside(CAMERA_PADDING + FIT_PADDING)

      it.state.fitCameraToBounds(BOUNDS, 0.0, 0.0, FIT_PADDING)
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

      it.state.fitCameraToBounds(ANTIMERIDIAN_BOUNDS, 0.0, 0.0, PaddingValues(0.dp))
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
      it.state.setCameraPosition(START)
      it.awaitMapReady()
      it.pumpUntil("the camera padding to be applied") {
        it.cameraTargetMatches(START, CAMERA_PADDING)
      }

      it.awaitWhileRendering("the bounds animation to complete") {
        it.state.animateCameraToBounds(BOUNDS, 0.0, 0.0, FIT_PADDING, 200.milliseconds)
      }

      val firstFit = it.session.getCameraPosition()
      it.assertCameraTarget(firstFit, CAMERA_PADDING)
      it.assertBoundsInside(CAMERA_PADDING + FIT_PADDING)

      it.awaitWhileRendering("the repeated bounds animation to complete") {
        it.state.animateCameraToBounds(BOUNDS, 0.0, 0.0, FIT_PADDING, 200.milliseconds)
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
        it.state.animateCameraPosition(TARGET, 200.milliseconds)
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
          launch(Dispatchers.Default) {
            it.state.animateCameraPosition(TARGET, 2.seconds)
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
        it.state.animateCameraPosition(TARGET, 0.milliseconds)
      }
      assertNear(
        TARGET.zoom,
        it.session.getCameraPosition().zoom,
        "the instant animation should reach its target",
      )
    }
  }

  /**
   * Replacing a transition ends the old one, and that end belongs to the transition it replaced.
   */
  @Test
  fun a_replacement_animation_waits_for_its_own_end(): MapTestResult = runMapTest {
    // A zero animator duration scale makes every animation a jump, so nothing is in flight to
    // cancel or to keep running.
    if (systemAnimatorDurationScale() == 0f) skipMapTest("System animations are disabled")
    createMapFixture().use {
      it.startAtOrigin()

      val superseded =
        launch(Dispatchers.Default) {
          it.state.animateCameraPosition(TARGET, 10.seconds)
        }
      it.awaitCameraMoving()

      val replacement =
        launch(Dispatchers.Default) {
          it.state.animateCameraPosition(MIDPOINT, 2.seconds)
        }
      it.pumpUntil("the superseded animation to cancel") { superseded.isCompleted }

      assertTrue(superseded.isCancelled, "the replacement should cancel the prior mutation")

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
  fun cancelling_an_animation_stops_the_camera_and_leaves_nothing_registered(): MapTestResult =
    runMapTest {
      // A zero animator duration scale lands the camera on its target before a cancel can arrive.
      if (systemAnimatorDurationScale() == 0f) skipMapTest("System animations are disabled")
      createMapFixture().use {
        it.startAtOrigin()
        it.events.clear()

        val animation =
          launch(Dispatchers.Default) {
            it.state.animateCameraPosition(TARGET, 30.seconds)
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
          it.state.animateCameraPosition(TARGET, 200.milliseconds)
        }
        assertNear(
          TARGET.zoom,
          it.session.getCameraPosition().zoom,
          "a later animation should still complete",
        )
      }
    }

  private suspend fun MapFixture.startAtOrigin() {
    // GL JS renders nothing without a style.
    loadStyle(BaseStyle.Empty)
    state.setCameraPosition(START)
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
    val BOUNDS_NW = Position(longitude = BOUNDS.west, latitude = BOUNDS.north)
    val BOUNDS_SE = Position(longitude = BOUNDS.east, latitude = BOUNDS.south)
    /** The vertices touch every side of [BOUNDS] without reaching a corner. */
    val DIAMOND_ROUTE =
      listOf(
        Position(longitude = -5.0, latitude = 0.0),
        Position(longitude = 0.0, latitude = 5.0),
        Position(longitude = 5.0, latitude = 0.0),
        Position(longitude = 0.0, latitude = -5.0),
      )
    val ANTIMERIDIAN_ROUTE =
      listOf(
        Position(longitude = 170.0, latitude = -10.0),
        Position(longitude = 190.0, latitude = 10.0),
      )
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
      assertPositionsInside(listOf(BOUNDS.southwest, BOUNDS.northeast), padding)
    }

    fun MapFixture.assertPositionsInside(positions: List<Position>, padding: PaddingValues) {
      val viewport = requireNotNull(session.getViewport())
      val tolerance = 1.0
      for (position in positions) {
        val point = requireNotNull(session.screenLocationFromPosition(position))
        assertTrue(point.x.value + tolerance >= padding.left().value, "$position is left of view")
        assertTrue(
          point.x.value - tolerance <= viewport.size.width.value - padding.right().value,
          "$position is right of view",
        )
        assertTrue(
          point.y.value + tolerance >= padding.calculateTopPadding().value,
          "$position is above view",
        )
        assertTrue(
          point.y.value - tolerance <=
            viewport.size.height.value - padding.calculateBottomPadding().value,
          "$position is below view",
        )
      }
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
