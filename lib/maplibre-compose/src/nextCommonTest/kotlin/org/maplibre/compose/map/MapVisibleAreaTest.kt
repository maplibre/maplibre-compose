package org.maplibre.compose.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraProjection
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
      it.pumpUntil("the camera to apply") {
        abs(it.session.getCameraPosition().zoom - CAMERA.zoom) < 0.01
      }

      val box = it.session.getVisibleBoundingBox()
      assertContains(box, CAMERA.target, "the camera target")
      assertTrue(box.northeast.latitude > box.southwest.latitude, "the box should span latitude")
      assertTrue(box.northeast.longitude > box.southwest.longitude, "the box should span longitude")
    }
  }

  @Test
  fun the_bounding_box_covers_the_region_of_a_rotated_and_tilted_camera(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.session.setBaseStyle(BaseStyle.Empty)
        it.awaitMapReady()
        it.session.setCameraPosition(ROTATED_CAMERA)
        it.pumpUntil("the camera to rotate") {
          abs(it.session.getCameraPosition().bearing - ROTATED_CAMERA.bearing) < 0.01
        }

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
        it.pumpUntil("the camera to rotate") {
          abs(it.session.getCameraPosition().bearing - ROTATED_CAMERA.bearing) < 0.01
        }

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
  fun the_projection_query_matches_the_session(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.session.setBaseStyle(BaseStyle.Empty)
      it.awaitMapReady()
      it.session.setCameraPosition(ROTATED_CAMERA)
      it.pumpUntil("the camera to rotate") {
        abs(it.session.getCameraPosition().bearing - ROTATED_CAMERA.bearing) < 0.01
      }

      val projection = CameraProjection(it.session)
      assertNear(it.session.getVisibleBoundingBox(), projection.queryVisibleBoundingBox())
    }
  }

  private companion object {
    val CAMERA = CameraPosition(target = Position(11.0, 47.0), zoom = 5.0)
    val ROTATED_CAMERA =
      CameraPosition(target = Position(11.0, 47.0), zoom = 5.0, bearing = 45.0, tilt = 40.0)

    const val TOLERANCE = 1e-6

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
