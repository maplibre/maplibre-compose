package org.maplibre.compose.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * The visible bounding box must cover the whole viewport, which means covering all four corners of
 * the visible region even when a bearing or tilt moves them off the axis-aligned diagonal.
 */
class MapVisibleAreaTest {

  @Test
  fun the_bounding_box_frames_the_camera_target(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.session.setBaseStyle(BaseStyle.Empty)
      it.awaitMapReady()
      it.session.setCameraPosition(CAMERA)
      it.pumpUntil("the camera to apply") { it.session.hasNativeCamera(CAMERA) }

      val box = it.session.getVisibleBoundingBox()
      assertContains(box, CAMERA.target, "the camera target")
      assertTrue(box.northeast.latitude > box.southwest.latitude, "the box should span latitude")
      assertTrue(box.northeast.longitude > box.southwest.longitude, "the box should span longitude")
    }
  }

  @Test
  fun the_camera_and_bounding_box_come_from_one_native_snapshot(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.session.setBaseStyle(BaseStyle.Empty)
      it.awaitMapReady()
      it.session.setCameraPosition(CAMERA)

      val camera = it.session.getCameraPosition()
      val box = it.session.getVisibleBoundingBox()
      val cameraApplied =
        abs(camera.zoom - CAMERA.zoom) < 0.01 && abs(camera.bearing - CAMERA.bearing) < 0.01
      val latSpan = box.northeast.latitude - box.southwest.latitude
      val boxApplied = latSpan > 0.01 && latSpan < 40.0
      assertTrue(
        cameraApplied == boxApplied,
        "the camera and bounding box should update together, camera applied=$cameraApplied box applied=$boxApplied (camera=$camera box=$box)",
      )

      it.pumpUntil("the camera to apply") { it.session.hasNativeCamera(CAMERA) }
    }
  }

  @Test
  fun the_bounding_box_covers_the_region_of_a_rotated_and_tilted_camera(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.session.setBaseStyle(BaseStyle.Empty)
        it.awaitMapReady()
        it.session.setCameraPosition(ROTATED_CAMERA)
        it.pumpUntil("the camera to rotate") { it.session.hasNativeCamera(ROTATED_CAMERA) }

        val region = it.session.getVisibleRegion()
        val box = it.session.getVisibleBoundingBox()
        assertContains(box, region.farLeft, "the far left corner")
        assertContains(box, region.farRight, "the far right corner")
        assertContains(box, region.nearLeft, "the near left corner")
        assertContains(box, region.nearRight, "the near right corner")
      }
    }

  @Test
  fun the_region_of_a_rotated_and_tilted_camera_is_a_proper_quadrilateral(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.session.setBaseStyle(BaseStyle.Empty)
        it.awaitMapReady()
        it.session.setCameraPosition(ROTATED_CAMERA)
        it.pumpUntil("the camera to rotate") { it.session.hasNativeCamera(ROTATED_CAMERA) }

        val region = it.session.getVisibleRegion()
        val corners = region.corners()
        assertTrue(
          corners.distinct().size == 4,
          "the corners should be distinct, was $region",
        )
        // Tilt widens the far edge relative to the near edge.
        assertTrue(
          span(region.farLeft, region.farRight) > span(region.nearLeft, region.nearRight),
          "the far edge should be wider than the near edge, was $region",
        )
      }
    }

  @Test
  fun the_bounding_box_stays_narrow_across_the_antimeridian(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.session.setBaseStyle(BaseStyle.Empty)
      it.awaitMapReady()
      it.session.setCameraPosition(ANTIMERIDIAN_CAMERA)
      it.pumpUntil("the camera to apply") { it.session.hasNativeCamera(ANTIMERIDIAN_CAMERA) }

      val box = it.session.getVisibleBoundingBox()
      // A wrapped hull would span nearly the whole world instead of the short interval, which may
      // extend past ±180.
      assertTrue(
        box.northeast.longitude - box.southwest.longitude < 90.0,
        "the box should span the short way around the antimeridian, was $box",
      )
      assertContains(box, Position(ANTIMERIDIAN_CAMERA.target.longitude, 47.0), "the target")
    }
  }

  @Test
  fun the_viewport_matches_the_session(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.session.setBaseStyle(BaseStyle.Empty)
      it.awaitMapReady()
      it.session.setCameraPosition(ROTATED_CAMERA)
      it.pumpUntil("the camera to rotate") { it.session.hasNativeCamera(ROTATED_CAMERA) }

      val viewport = assertNotNull(it.session.getViewport())
      assertNear(it.session.getVisibleBoundingBox(), viewport.visibleBoundingBox)
      assertTrue(
        viewport.size.width.value > 0f && viewport.size.height.value > 0f,
        "the viewport should carry the map's size, was ${viewport.size}",
      )
    }
  }

  private companion object {
    val CAMERA = CameraPosition(target = Position(11.0, 47.0), zoom = 5.0)
    val ANTIMERIDIAN_CAMERA = CameraPosition(target = Position(179.9, 47.0), zoom = 5.0)
    val ROTATED_CAMERA =
      CameraPosition(target = Position(11.0, 47.0), zoom = 5.0, bearing = 45.0, tilt = 40.0)

    const val TOLERANCE = 1e-6

    /**
     * Camera, bounding box, and region update together after native applies a position. Wait for a
     * zoomed-in box so the assertions read that snapshot rather than the startup viewport.
     */
    fun MapAdapter.hasNativeCamera(camera: CameraPosition): Boolean {
      if (abs(getCameraPosition().zoom - camera.zoom) >= 0.01) return false
      if (abs(getCameraPosition().bearing - camera.bearing) >= 0.01) return false
      val box = getVisibleBoundingBox()
      val latSpan = box.northeast.latitude - box.southwest.latitude
      return latSpan > 0.01 && latSpan < 40.0
    }

    fun VisibleRegion.corners() = listOf(farLeft, farRight, nearLeft, nearRight)

    fun span(a: Position, b: Position) = abs(a.longitude - b.longitude)

    fun assertContains(box: BoundingBox, position: Position, what: String) {
      assertTrue(
        position.longitude in
          (box.southwest.longitude - TOLERANCE)..(box.northeast.longitude + TOLERANCE) &&
          position.latitude in
            (box.southwest.latitude - TOLERANCE)..(box.northeast.latitude + TOLERANCE),
        "the bounding box should contain $what: $position was outside $box",
      )
    }

    fun assertNear(expected: BoundingBox, actual: BoundingBox) {
      val corners =
        listOf(
          expected.southwest.longitude to actual.southwest.longitude,
          expected.southwest.latitude to actual.southwest.latitude,
          expected.northeast.longitude to actual.northeast.longitude,
          expected.northeast.latitude to actual.northeast.latitude,
        )
      assertTrue(
        corners.all { (e, a) -> abs(e - a) < TOLERANCE },
        "the queried box should match the session's: $actual was not $expected",
      )
    }
  }
}
