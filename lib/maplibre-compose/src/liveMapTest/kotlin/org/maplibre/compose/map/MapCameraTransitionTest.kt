package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.camera.CameraAnchor
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.systemAnimatorDurationScale
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapLibreFlavor
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.mapLibreFlavor
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.testing.skipMapTest
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * Both backends advance a transition only from inside a render, so every test renders as it waits.
 */
class MapCameraTransitionTest {

  @Test
  fun polar_bounds_fit_like_their_mercator_clamped_bounds(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.startAt(Start)
      val latitudeLimit = 85.0511287798066
      for (bounds in
        listOf(
          BoundingBox(west = -180.0, south = -90.0, east = 180.0, north = 90.0),
          BoundingBox(west = -20.0, south = 70.0, east = 20.0, north = 90.0),
          BoundingBox(west = -20.0, south = -90.0, east = 20.0, north = -70.0),
        )) {
        val clamped =
          BoundingBox(
            west = bounds.west,
            south = bounds.south.coerceIn(-latitudeLimit, latitudeLimit),
            east = bounds.east,
            north = bounds.north.coerceIn(-latitudeLimit, latitudeLimit),
          )
        val padding = DpPadding(left = 20.dp, bottom = 30.dp)
        val expected = fixture.state.cameraForBounds(clamped, cameraPadding = padding)
        val actual = fixture.state.cameraForBounds(bounds, cameraPadding = padding)
        assertSameFit(expected, actual, "polar bounds $bounds")
        assertEquals(padding, actual.padding)
      }
    }
  }

  @Test
  fun camera_padding_and_viewport_insets_have_independent_ownership(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val padding = DpPadding(left = 20.dp, bottom = 80.dp)
      val camera = Start.copy(padding = padding)
      val framing = PaddingValues.Absolute(left = 20.dp, bottom = 80.dp)
      fixture.session.setViewportInsets(ViewportInsets)
      fixture.startAt(camera)
      fixture.pumpUntil("combined camera padding") {
        fixture.cameraTargetMatches(camera, ViewportInsets + framing)
      }
      assertEquals(padding, fixture.session.getCameraPosition().padding)
      fixture.session.setViewportInsets(ReplacementViewportInsets)
      fixture.pumpUntil("replacement viewport insets") {
        fixture.cameraTargetMatches(camera, ReplacementViewportInsets + framing)
      }
      assertEquals(padding, fixture.session.getCameraPosition().padding)
      val fit = fixture.state.cameraForBounds(boundingBox = Bounds, fitPadding = FitPadding)
      assertEquals(padding, fit.padding)
      fixture.state.setCameraPosition(fit)
      fixture.pump(frames = 3)
      fixture.assertBoundsInside(ReplacementViewportInsets + framing + FitPadding)
      fixture.assertCameraTarget(fit, ReplacementViewportInsets + framing)
      fixture.state.setCameraPosition(fit.copy(padding = DpPadding.Zero))
      fixture.pump(frames = 3)
      fixture.assertCameraTarget(fit, ReplacementViewportInsets)
      assertEquals(DpPadding.Zero, fixture.session.getCameraPosition().padding)
    }
  }

  @Test
  fun a_camera_animation_reports_destination_padding_without_viewport_insets(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.session.setViewportInsets(ViewportInsets)
        fixture.startAt(Start)
        val target = Target.copy(padding = DpPadding(bottom = 100.dp))
        val animation = launch {
          fixture.state.animateCamera(target.toCameraUpdate(), CameraAnimation.Ease(1.seconds))
        }
        fixture.pumpUntil("camera padding animation") { animation.isCompleted }
        assertFalse(animation.isCancelled)
        assertEquals(target.padding, fixture.session.getCameraPosition().padding)
        fixture.assertCameraTarget(target, ViewportInsets + PaddingValues(bottom = 100.dp))
      }
    }

  @Test
  fun fit_queries_use_destination_padding_without_moving_the_live_camera(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        val initial = Start.copy(padding = DpPadding(left = 20.dp))
        val destination = DpPadding(right = 45.dp, bottom = 100.dp)
        val framing = PaddingValues.Absolute(right = 45.dp, bottom = 100.dp)
        val corners = listOf(BoundsNw, Bounds.northeast, BoundsSe, Bounds.southwest, BoundsNw)
        fixture.session.setViewportInsets(ViewportInsets)
        fixture.startAt(initial)
        val before = fixture.session.getCameraPosition()
        val queries =
          listOf(
            fixture.state.cameraForBounds(
              boundingBox = Bounds,
              bearing = 35.0,
              cameraPadding = destination,
              fitPadding = FitPadding,
            ),
            fixture.state.cameraForGeometry(
              geometry = Polygon(listOf(corners)),
              bearing = 35.0,
              cameraPadding = destination,
              fitPadding = FitPadding,
            ),
            fixture.state.cameraForCoordinates(
              coordinates = corners,
              bearing = 35.0,
              cameraPadding = destination,
              fitPadding = FitPadding,
            ),
          )
        fixture.pump(frames = 2)
        assertEquals(
          before,
          fixture.session.getCameraPosition(),
          "the queries moved the live camera",
        )
        for (camera in queries) {
          assertEquals(destination, camera.padding)
          assertSameFit(queries.first(), camera, "fit queries disagree on destination padding")
        }
        fixture.state.setCameraPosition(queries.first())
        fixture.pump(frames = 3)
        fixture.assertCameraTarget(queries.first(), ViewportInsets + framing)
        fixture.assertPositionsInside(corners, ViewportInsets + framing + FitPadding)

        // Explicit destination padding and padding inherited from the applied camera must agree.
        val currentFit =
          fixture.state.cameraForBounds(
            boundingBox = Bounds,
            bearing = 35.0,
            fitPadding = FitPadding,
          )
        assertSameFit(
          queries.first(),
          currentFit,
          "explicit and inherited padding produce different fits",
        )
        assertEquals(destination, currentFit.padding)

        fixture.state.fitCameraToBounds(
          boundingBox = Bounds,
          cameraPadding = DpPadding.Zero,
          fitPadding = FitPadding,
        )
        fixture.pump(frames = 3)
        assertEquals(DpPadding.Zero, fixture.session.getCameraPosition().padding)
        fixture.assertBoundsInside(ViewportInsets + FitPadding)
      }
    }

  @Test
  fun a_bounds_query_can_be_applied_with_transient_padding(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.session.setViewportInsets(ViewportInsets)
      it.startAt(Start)
      it.pumpUntil("the viewport insets to be applied") {
        it.cameraTargetMatches(Start, ViewportInsets)
      }
      val before = it.session.getCameraPosition()
      val camera = it.state.cameraForBounds(boundingBox = Bounds, fitPadding = FitPadding)
      it.pump(frames = 2)
      assertSameFit(before, it.session.getCameraPosition(), "the query moved the camera")

      it.state.setCameraPosition(camera)
      it.pumpUntil("the calculated camera to be applied") {
        abs(it.session.getCameraPosition().zoom - camera.zoom) < 0.01
      }
      it.assertCameraTarget(camera, ViewportInsets)
      it.assertBoundsInside(ViewportInsets + FitPadding)

      it.state.fitCameraToBounds(boundingBox = Bounds, fitPadding = FitPadding)
      it.pump(frames = 2)
      assertSameFit(camera, it.session.getCameraPosition(), "the query disagrees with the fit")
    }
  }

  /** Perspective widens the near edge of the box, so a pitched fit differs from the flat one. */
  @Test
  fun a_pitched_bounds_fit_keeps_the_bounds_inside_the_padded_viewport(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.startAtOrigin()
        val flat = it.state.cameraForBounds(boundingBox = Bounds, fitPadding = FitPadding)
        val camera =
          it.state.cameraForBounds(
            boundingBox = Bounds,
            bearing = 35.0,
            pitch = 50.0,
            fitPadding = FitPadding,
          )
        assertNear(50.0, camera.pitch, "the query pitch")
        assertTrue(
          abs(camera.zoom - flat.zoom) > 0.05,
          "the pitched fit should differ from the flat fit (${camera.zoom} vs ${flat.zoom})",
        )

        it.state.setCameraPosition(camera)
        it.pumpUntil("the calculated camera to be applied") {
          abs(it.session.getCameraPosition().pitch - 50.0) < 0.01
        }
        val corners = listOf(BoundsNw, Bounds.northeast, BoundsSe, Bounds.southwest)
        it.assertPositionsInside(corners, FitPadding.asPaddingValues())
      }
    }

  @Test
  fun a_pitched_bounds_query_uses_destination_padding(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()
      val destination = DpPadding(left = 30.dp, bottom = 60.dp)
      it.session.setViewportInsets(ViewportInsets)
      val before = it.session.getCameraPosition()
      val camera =
        it.state.cameraForBounds(
          boundingBox = Bounds,
          bearing = 35.0,
          pitch = 50.0,
          cameraPadding = destination,
          fitPadding = FitPadding,
        )
      assertEquals(before, it.session.getCameraPosition(), "the query moved the live camera")
      assertEquals(destination, camera.padding)
      assertNear(50.0, camera.pitch, "the query pitch")

      it.state.setCameraPosition(camera)
      it.pumpUntil("the calculated camera to be applied") {
        abs(it.session.getCameraPosition().pitch - 50.0) < 0.01
      }
      val corners = listOf(BoundsNw, Bounds.northeast, BoundsSe, Bounds.southwest)
      val effective = ViewportInsets + destination.asPaddingValues()
      it.assertCameraTarget(camera, effective)
      it.assertPositionsInside(corners, effective + FitPadding)
    }
  }

  @Test
  fun a_bounds_query_waits_for_the_first_viewport(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val query =
        async(start = CoroutineStart.UNDISPATCHED) {
          fixture.state.cameraForBounds(AntimeridianBounds)
        }
      assertFalse(query.isCompleted)
      fixture.awaitMapReady()
      val camera = withTimeout(30.seconds) { query.await() }
      assertTrue(abs(abs(camera.target.longitude) - 180.0) < 1.0)
      assertTrue(camera.zoom > Start.zoom)
    }
  }

  @Test
  fun a_bounds_query_does_not_interrupt_an_animation(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()
      val animation = launch {
        it.state.animateCamera(Target.toCameraUpdate(), CameraAnimation.Fly(2.seconds))
      }
      it.awaitCameraMoving()
      it.state
        .cameraForBounds(
          boundingBox = Bounds,
          bearing = 35.0,
          pitch = 20.0,
          cameraPadding = DpPadding(bottom = 80.dp),
        )
        .also { camera ->
          assertEquals(DpPadding(bottom = 80.dp), camera.padding)
          assertNear(35.0, camera.bearing, "the query bearing")
          assertNear(20.0, camera.pitch, "the query pitch")
        }
      it.pumpUntil("the animation to complete after the query") { animation.isCompleted }
      assertFalse(animation.isCancelled)
      assertNear(Target.zoom, it.session.getCameraPosition().zoom, "the animation target")
    }
  }

  @Test
  fun a_geometry_query_matches_the_bounds_query_for_the_box_corners(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()
      val corners =
        Polygon(listOf(listOf(BoundsNw, Bounds.northeast, BoundsSe, Bounds.southwest, BoundsNw)))
      val fromBounds =
        it.state.cameraForBounds(boundingBox = Bounds, bearing = 35.0, fitPadding = FitPadding)
      val fromGeometry =
        it.state.cameraForGeometry(geometry = corners, bearing = 35.0, fitPadding = FitPadding)
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
        val fromBounds =
          it.state.cameraForBounds(boundingBox = Bounds, bearing = 45.0, fitPadding = FitPadding)
        val camera =
          it.state.cameraForCoordinates(
            coordinates = DiamondRoute,
            bearing = 45.0,
            fitPadding = FitPadding,
          )
        assertTrue(
          camera.zoom > fromBounds.zoom + 0.5,
          "the diamond fit should zoom in past the bounds fit (${camera.zoom} vs ${fromBounds.zoom})",
        )

        it.state.setCameraPosition(camera)
        it.pumpUntil("the calculated camera to be applied") {
          abs(it.session.getCameraPosition().zoom - camera.zoom) < 0.01
        }
        it.assertPositionsInside(DiamondRoute, FitPadding.asPaddingValues())
      }
    }

  @Test
  fun a_coordinates_query_crosses_the_antimeridian_with_continuous_longitudes(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.startAtOrigin()
        val camera = it.state.cameraForCoordinates(AntimeridianRoute)
        assertTrue(
          abs(abs(camera.target.longitude) - 180.0) < 1.0,
          "the target should sit on the antimeridian, but was ${camera.target}",
        )
        assertTrue(camera.zoom > Start.zoom)
      }
    }

  @Test
  fun a_bounds_jump_keeps_fit_padding_transient(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.session.setViewportInsets(ViewportInsets)
      it.startAt(Start)
      it.pumpUntil("the viewport insets to be applied") {
        it.cameraTargetMatches(Start, ViewportInsets)
      }

      it.state.fitCameraToBounds(
        boundingBox = Bounds,
        bearing = 0.0,
        pitch = 0.0,
        fitPadding = FitPadding,
      )
      it.pumpUntil("the bounds fit to be applied") {
        abs(it.session.getCameraPosition().zoom - Start.zoom) > 0.1
      }
      val fitAfterPadding = it.session.getCameraPosition()
      it.assertCameraTarget(fitAfterPadding, ViewportInsets)
      it.assertBoundsInside(ViewportInsets + FitPadding)

      it.state.fitCameraToBounds(
        boundingBox = Bounds,
        bearing = 0.0,
        pitch = 0.0,
        fitPadding = FitPadding,
      )
      it.pump(frames = 2)
      val repeatedFit = it.session.getCameraPosition()
      assertSameFit(fitAfterPadding, repeatedFit, "repeating the bounds fit changed its camera")

      it.session.setViewportInsets(ReplacementViewportInsets)
      it.pumpUntil("the replacement viewport insets to be applied") {
        it.cameraTargetMatches(fitAfterPadding, ReplacementViewportInsets)
      }
    }
  }

  @Test
  fun a_bounds_fit_crosses_the_antimeridian_the_short_way(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()

      it.state.fitCameraToBounds(
        boundingBox = AntimeridianBounds,
        bearing = 0.0,
        pitch = 0.0,
        cameraPadding = DpPadding(left = 20.dp, right = 20.dp),
        fitPadding = DpPadding.Zero,
      )
      it.pumpUntil("the antimeridian bounds fit to be applied") {
        val camera = it.session.getCameraPosition()
        abs(abs(camera.target.longitude) - 180.0) < 1.0 && camera.zoom > Start.zoom
      }
    }
  }

  @Test
  fun a_bounds_animation_keeps_fit_padding_transient(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.session.setViewportInsets(ViewportInsets)
      it.startAt(Start)
      it.pumpUntil("the viewport insets to be applied") {
        it.cameraTargetMatches(Start, ViewportInsets)
      }

      it.awaitWhileRendering("the bounds animation to complete") {
        it.state.animateCameraToBounds(
          boundingBox = Bounds,
          bearing = 0.0,
          pitch = 0.0,
          cameraPadding = DpPadding(bottom = 80.dp),
          fitPadding = FitPadding,
          animation = CameraAnimation.Fly(200.milliseconds),
        )
      }

      val firstFit = it.session.getCameraPosition()
      assertEquals(DpPadding(bottom = 80.dp), firstFit.padding)
      val effective = ViewportInsets + PaddingValues(bottom = 80.dp)
      it.assertCameraTarget(firstFit, effective)
      it.assertBoundsInside(effective + FitPadding)

      it.awaitWhileRendering("the repeated bounds animation to complete") {
        it.state.animateCameraToBounds(
          boundingBox = Bounds,
          bearing = 0.0,
          pitch = 0.0,
          fitPadding = FitPadding,
          animation = CameraAnimation.Fly(200.milliseconds),
        )
      }

      assertSameFit(
        firstFit,
        it.session.getCameraPosition(),
        "repeating the bounds animation changed its camera",
      )
    }
  }

  /** A flight without a duration must use the engine's animated path and reach its target. */
  @Test
  fun a_flight_paced_by_speed_animates_and_lands_on_its_target(): MapTestResult = runMapTest {
    if (systemAnimatorDurationScale() == 0f) skipMapTest("System animations are disabled")
    createMapFixture().use {
      it.startAt(FlightStart)
      it.engineEvents.clear()

      it.awaitWhileRendering("the flight to complete") {
        it.state.animateCamera(
          FlightTarget.toCameraUpdate(),
          CameraAnimation.Fly(speed = 20.0),
        )
      }

      // The engine uses wall time: a slow renderer can miss any intermediate camera position.
      // Native reports whether the move was animated; GL JS does not expose this distinction.
      if (mapLibreFlavor == MapLibreFlavor.Native) {
        assertTrue(
          it.engineEvents.any { event -> event == MapEvent.CameraMoveStarted(animated = true) },
          "the speed-paced flight was not animated: ${it.engineEvents}",
        )
      }
      it.assertLanded(FlightTarget, "the flight")
    }
  }

  /** An eased bounds animation lands on the same fit as a flight to the same bounds. */
  @Test
  fun an_eased_bounds_animation_lands_on_the_fit(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()
      val fit = it.state.cameraForBounds(boundingBox = Bounds, fitPadding = FitPadding)

      it.awaitWhileRendering("the eased bounds animation to complete") {
        it.state.animateCameraToBounds(
          boundingBox = Bounds,
          fitPadding = FitPadding,
          animation = CameraAnimation.Ease(200.milliseconds),
        )
      }

      assertSameFit(fit, it.session.getCameraPosition(), "the eased bounds animation")
    }
  }

  @Test
  fun reapplying_identical_camera_constraints_does_not_cancel_an_animation(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.startAtOrigin()
        it.session.applyTestConstraints()
        it.pump(frames = 2)

        val animation = launch {
          it.state.animateCamera(Target.toCameraUpdate(), CameraAnimation.Fly(2.seconds))
        }
        it.awaitCameraMoving()
        it.session.applyTestConstraints()
        it.pumpUntil("the animation to complete after the constraints repeat") {
          animation.isCompleted
        }

        assertNear(
          Target.zoom,
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
        it.session.setCameraConstraints(TestConstraints)
        it.pump(frames = 2)

        it.session.setCameraConstraints(DisjointZoomConstraints)
        it.pumpUntil("the camera to adopt the disjoint zoom range") {
          abs(it.session.getCameraPosition().zoom - DisjointZoomConstraints.minZoom) < 0.01
        }
      }
    }

  /** A zero duration overrides speed and emits its event during the call without deadlocking. */
  @Test
  fun a_zero_duration_animation_overrides_speed_and_completes(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.startAtOrigin()

      it.awaitWhileRendering("the instant animation to complete") {
        it.state.animateCamera(
          Target.toCameraUpdate(),
          CameraAnimation.Fly(duration = 0.milliseconds, speed = 0.001),
        )
      }
      assertNear(
        Target.zoom,
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

      val superseded = launch {
        it.state.animateCamera(Target.toCameraUpdate(), CameraAnimation.Fly(10.seconds))
      }
      it.awaitCameraMoving()

      val replacement = launch {
        it.state.animateCamera(Midpoint.toCameraUpdate(), CameraAnimation.Fly(2.seconds))
      }
      it.pumpUntil("the superseded animation to cancel") { superseded.isCompleted }

      assertFalse(superseded.isCancelled, "supersession completes the prior command normally")

      assertFalse(
        replacement.isCompleted,
        "the replacement should still be running when the animation it replaced ends",
      )

      it.pumpUntil("the replacement animation to complete") { replacement.isCompleted }
      assertNear(
        Midpoint.zoom,
        it.session.getCameraPosition().zoom,
        "the replacement should have reached its own target",
      )
    }
  }

  @Test
  fun cancelling_an_animation_withdraws_its_waiter_without_stopping_motion(): MapTestResult =
    runMapTest {
      // A zero animator duration scale lands the camera on its target before a cancel can arrive.
      if (systemAnimatorDurationScale() == 0f) skipMapTest("System animations are disabled")
      createMapFixture().use {
        it.startAtOrigin()
        it.events.clear()

        val animation = launch {
          it.state.animateCamera(Target.toCameraUpdate(), CameraAnimation.Ease(1.seconds))
        }
        it.awaitCameraMoving()
        animation.cancel()
        it.pumpUntil("the cancelled animation to unwind") { animation.isCompleted }

        it.pumpUntil("the abandoned animation to reach its target") {
          abs(it.session.getCameraPosition().zoom - Target.zoom) < 0.01
        }
        assertTrue(animation.isCancelled)

        it.awaitWhileRendering("a later animation to complete") {
          it.state.animateCamera(Target.toCameraUpdate(), CameraAnimation.Fly(200.milliseconds))
        }
        assertNear(
          Target.zoom,
          it.session.getCameraPosition().zoom,
          "a later animation should still complete",
        )
      }
    }

  @Test
  fun stopping_an_animation_retains_its_position_and_allows_a_new_command(): MapTestResult =
    runMapTest {
      if (systemAnimatorDurationScale() == 0f) skipMapTest("System animations are disabled")
      createMapFixture().use {
        it.startAtOrigin()
        val animation = launch {
          it.state.animateCamera(Target.toCameraUpdate(), CameraAnimation.Ease(30.seconds))
        }
        it.awaitCameraMoving()
        it.state.stopCameraMovement()
        it.pumpUntil("the stopped animation to cancel") {
          animation.isCompleted && !it.state.isCameraMoving
        }
        assertTrue(animation.isCancelled)
        val stopped = it.session.getCameraPosition()
        assertTrue(stopped.zoom < Target.zoom - 0.1)
        it.pump(frames = 10)
        assertSameFit(stopped, it.session.getCameraPosition(), "movement after stop")
        assertSameFit(stopped, it.state.cameraPosition, "retained stopped camera")

        // Queue a stop and a replacement without rendering between them.
        it.state.stopCameraMovement()
        it.awaitWhileRendering("the command racing the stop to complete") {
          it.state.animateCamera(Target.toCameraUpdate(), CameraAnimation.Ease(200.milliseconds))
        }
        it.assertLanded(Target, "the newer command")
      }
    }

  @Test
  fun anchored_easing_preserves_a_screen_point_with_zoom_rotation_and_pitch(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.startAt(Start.copy(zoom = 8.0, bearing = 25.0, pitch = 35.0))
        fixture.session.setViewportInsets(ViewportInsets)
        fixture.pump(frames = 3)
        val point = DpOffset(190.dp, 300.dp)
        val location = requireNotNull(fixture.session.positionFromScreenLocation(point))
        val animation = launch {
          fixture.state.animateCameraAround(
            CameraAnchor.Screen(point),
            zoom = 10.0,
            bearing = 110.0,
            pitch = 55.0,
            animation = CameraAnimation.Ease(1.seconds),
          )
        }
        fixture.pumpUntil("the anchored ease to finish") {
          fixture.assertAnchor(location, point)
          animation.isCompleted
        }
        assertFalse(animation.isCancelled)
        fixture.assertAnchor(location, point)
        val camera = fixture.session.getCameraPosition()
        assertNear(10.0, camera.zoom, "zoom")
        assertNear(110.0, camera.bearing, "bearing")
        assertNear(55.0, camera.pitch, "pitch")
        fixture.assertCameraTarget(camera, ViewportInsets)
      }
    }

  @Test
  fun a_geographic_anchor_uses_the_nearest_world_copy_and_an_instant_anchored_endpoint():
    MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.startAt(Start.copy(target = Position(179.0, 0.0), zoom = 3.0, pitch = 40.0))
      val location = Position(-179.0, 1.0)
      val point = requireNotNull(fixture.session.screenLocationFromPosition(location))
      fixture.awaitWhileRendering("the instant anchored zoom") {
        fixture.state.animateCameraAround(
          CameraAnchor.Geographic(location),
          zoom = 5.0,
          animation = CameraAnimation.Ease(0.milliseconds),
        )
      }
      fixture.assertAnchor(location, point)
      assertNear(5.0, fixture.session.getCameraPosition().zoom, "zoom")
      assertNear(40.0, fixture.session.getCameraPosition().pitch, "omitted pitch")
      assertNear(0.0, fixture.session.getCameraPosition().bearing, "omitted bearing")
      val unwrapped = requireNotNull(fixture.session.positionFromScreenLocation(point))
      val center = fixture.session.getCameraPosition().target.longitude
      val expectedLongitude =
        location.longitude + 360.0 * kotlin.math.round((center - location.longitude) / 360.0)
      assertNear(
        expectedLongitude,
        unwrapped.longitude,
        "unprojection must retain the actual world copy",
      )
      fixture.awaitWhileRendering("another anchored move from the unwrapped center") {
        fixture.state.animateCameraAround(
          CameraAnchor.Geographic(location),
          bearing = 45.0,
          animation = CameraAnimation.Ease(0.milliseconds),
        )
      }
      fixture.assertAnchor(location, point)
      assertNear(5.0, fixture.session.getCameraPosition().zoom, "omitted zoom")
    }
  }

  @Test
  fun a_screen_anchor_can_select_a_distant_visible_world_copy(): MapTestResult = runMapTest {
    createMapFixture(MapExtent.fromLogical(width = 2048, height = 512, scaleFactor = 1.0)).use {
      fixture ->
      fixture.startAt(Start.copy(zoom = 1.0, bearing = 15.0))
      val point = DpOffset(50.dp, 256.dp)
      val location = requireNotNull(fixture.session.positionFromScreenLocation(point))
      assertTrue(abs(location.longitude) > 180.0, "the anchor must select another world copy")
      val animation = launch {
        fixture.state.animateCameraAround(
          CameraAnchor.Screen(point),
          zoom = 2.0,
          animation = CameraAnimation.Ease(500.milliseconds),
        )
      }
      fixture.pumpUntil("the anchored zoom in a repeated world") {
        val actual = requireNotNull(fixture.session.positionFromScreenLocation(point))
        val delta =
          ((actual.longitude - location.longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        assertNear(0.0, delta, "anchor longitude")
        assertNear(location.latitude, actual.latitude, "anchor latitude")
        animation.isCompleted
      }
      assertFalse(animation.isCancelled)
      assertNear(2.0, fixture.session.getCameraPosition().zoom, "zoom")
    }
  }

  @Test
  fun changing_viewport_insets_cancels_an_anchored_animation(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.assertAnchoredCancellation { fixture.session.setViewportInsets(ViewportInsets) }
    }
  }

  @Test
  fun resizing_cancels_an_anchored_animation(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.assertAnchoredCancellation {
        fixture.resize(MapExtent.fromLogical(width = 600, height = 400, scaleFactor = 1.0))
      }
    }
  }

  @Test
  fun changing_only_pixel_density_keeps_the_anchor_animation_running(): MapTestResult = runMapTest {
    if (systemAnimatorDurationScale() == 0f) skipMapTest("System animations are disabled")
    createMapFixture().use { fixture ->
      fixture.startAtOrigin()
      val point = DpOffset(190.dp, 300.dp)
      val location = requireNotNull(fixture.session.positionFromScreenLocation(point))
      val animation = launch {
        fixture.state.animateCameraAround(
          CameraAnchor.Screen(point),
          zoom = 5.0,
          animation = CameraAnimation.Ease(1.seconds),
        )
      }
      fixture.awaitCameraMoving()
      fixture.resize(MapFixture.RetinaExtent)
      fixture.pumpUntil("the animation to complete after changing density") {
        fixture.assertAnchor(location, point)
        animation.isCompleted
      }
      assertFalse(animation.isCancelled)
      assertNear(5.0, fixture.session.getCameraPosition().zoom, "target zoom")
    }
  }

  @Test
  fun an_anchored_animation_obeys_zoom_and_pitch_constraints(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.startAtOrigin()
      fixture.session.setCameraConstraints(TestConstraints.copy(maxZoom = 4.0, maxPitch = 45.0))
      fixture.pump(frames = 3)
      val point = DpOffset(190.dp, 300.dp)
      val location = requireNotNull(fixture.session.positionFromScreenLocation(point))
      fixture.awaitWhileRendering("the constrained anchored animation") {
        fixture.state.animateCameraAround(
          CameraAnchor.Screen(point),
          zoom = 10.0,
          pitch = 60.0,
          animation = CameraAnimation.Ease(0.milliseconds),
        )
      }
      assertNear(4.0, fixture.session.getCameraPosition().zoom, "constrained zoom")
      assertNear(45.0, fixture.session.getCameraPosition().pitch, "constrained pitch")
      fixture.assertAnchor(location, point)
    }
  }

  @Test
  fun a_new_camera_command_cancels_an_anchored_animation(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.assertAnchoredCancellation { fixture.state.setCameraPosition(Start) }
    }
  }

  @Test
  fun an_anchor_outside_the_viewport_is_rejected_without_moving_the_camera(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.startAtOrigin()
        assertFailsWith<IllegalArgumentException> {
          fixture.awaitWhileRendering("anchor validation") {
            fixture.state.animateCameraAround(
              CameraAnchor.Screen(DpOffset((-1).dp, 100.dp)),
              zoom = 10.0,
            )
          }
        }
        assertNear(Start.zoom, fixture.session.getCameraPosition().zoom, "unchanged zoom")
      }
    }

  private suspend fun MapFixture.assertAnchoredCancellation(change: () -> Unit) = coroutineScope {
    if (systemAnimatorDurationScale() == 0f) skipMapTest("System animations are disabled")
    startAtOrigin()
    val animation = launch {
      state.animateCameraAround(
        CameraAnchor.Screen(DpOffset(190.dp, 300.dp)),
        zoom = 10.0,
        animation = CameraAnimation.Ease(30.seconds),
      )
    }
    awaitCameraMoving()
    change()
    pumpUntil("the anchored animation to cancel") { animation.isCompleted }
    assertTrue(animation.isCancelled)
    val stopped = session.getCameraPosition()
    assertTrue(stopped.zoom < 9.0, "cancellation must not jump to the destination")
    var frames = 0
    pumpUntil("the camera to stay stopped") { ++frames >= 10 }
    assertNear(stopped.zoom, session.getCameraPosition().zoom, "stopped zoom")
  }

  private fun MapFixture.assertAnchor(location: Position, point: DpOffset) {
    val actual = requireNotNull(session.screenLocationFromPosition(location))
    assertTrue(
      abs(actual.x.value - point.x.value) < 0.5f && abs(actual.y.value - point.y.value) < 0.5f,
      "anchor moved from $point to $actual at ${session.getCameraPosition()}",
    )
  }

  private suspend fun MapFixture.startAtOrigin() = startAt(Start)

  private suspend fun MapFixture.startAt(position: CameraPosition) {
    // GL JS renders nothing without a style.
    loadStyle(BaseStyle.Empty)
    state.setCameraPosition(position)
    // Render first: before the map exists, a camera read echoes back whatever was last set.
    awaitMapReady()
    pumpUntil("the map to reach its starting camera") {
      val camera = session.getCameraPosition()
      abs(camera.zoom - position.zoom) < 0.001 &&
        abs(camera.target.latitude - position.target.latitude) < 0.001 &&
        abs(camera.target.longitude - position.target.longitude) < 0.001 &&
        abs(camera.bearing - position.bearing) < 0.001 &&
        abs(camera.pitch - position.pitch) < 0.001 &&
        camera.padding == position.padding
    }
  }

  private fun MapFixture.assertLanded(target: CameraPosition, description: String) {
    val camera = session.getCameraPosition()
    assertNear(target.zoom, camera.zoom, "$description target zoom")
    assertNear(target.target.latitude, camera.target.latitude, "$description target latitude")
    assertNear(target.target.longitude, camera.target.longitude, "$description target longitude")
  }

  private suspend fun MapFixture.awaitCameraMoving() {
    pumpUntil("the animation to start moving the camera") {
      abs(session.getCameraPosition().zoom - Start.zoom) > 0.01
    }
  }

  private fun MapAdapter.applyTestConstraints() {
    setCameraConstraints(TestConstraints)
  }

  private companion object {
    val Start = CameraPosition(target = Position(0.0, 0.0), zoom = 2.0)
    val Target = CameraPosition(target = Position(11.0, 47.0), zoom = 8.0)
    val Midpoint = CameraPosition(target = Position(5.0, 20.0), zoom = 5.0)
    // Far apart at their zoom, so a flight has to zoom out to cross the distance.
    val FlightStart = CameraPosition(target = Position(0.0, 0.0), zoom = 10.0)
    val FlightTarget = CameraPosition(target = Position(11.0, 47.0), zoom = 12.0)
    val Bounds =
      BoundingBox(
        southwest = Position(longitude = -5.0, latitude = -5.0),
        northeast = Position(longitude = 5.0, latitude = 5.0),
      )
    val TestConstraints =
      CameraConstraints(
        minZoom = 0.0,
        maxZoom = 20.0,
        minPitch = 0.0,
        maxPitch = 60.0,
        boundingBox = null,
      )
    val DisjointZoomConstraints = TestConstraints.copy(minZoom = 21.0, maxZoom = 22.0)
    val BoundsNw = Position(longitude = Bounds.west, latitude = Bounds.north)
    val BoundsSe = Position(longitude = Bounds.east, latitude = Bounds.south)
    /** The vertices touch every side of [Bounds] without reaching a corner. */
    val DiamondRoute =
      listOf(
        Position(longitude = -5.0, latitude = 0.0),
        Position(longitude = 0.0, latitude = 5.0),
        Position(longitude = 5.0, latitude = 0.0),
        Position(longitude = 0.0, latitude = -5.0),
      )
    val AntimeridianRoute =
      listOf(
        Position(longitude = 170.0, latitude = -10.0),
        Position(longitude = 190.0, latitude = 10.0),
      )
    val AntimeridianBounds =
      BoundingBox(
        southwest = Position(longitude = 170.0, latitude = -10.0),
        northeast = Position(longitude = -170.0, latitude = 10.0),
      )
    val ViewportInsets =
      PaddingValues.Absolute(left = 120.dp, top = 10.dp, right = 5.dp, bottom = 30.dp)
    val FitPadding = DpPadding(left = 40.dp, top = 20.dp, right = 70.dp, bottom = 60.dp)
    val ReplacementViewportInsets =
      PaddingValues.Absolute(left = 15.dp, top = 35.dp, right = 80.dp, bottom = 5.dp)

    fun DpPadding.asPaddingValues(): PaddingValues =
      PaddingValues.Absolute(left = left, top = top, right = right, bottom = bottom)

    operator fun PaddingValues.plus(other: DpPadding): PaddingValues =
      this + other.asPaddingValues()

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
      assertPositionsInside(listOf(Bounds.southwest, Bounds.northeast), padding)
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
