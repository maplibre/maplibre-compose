package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapLibreFlavor
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.mapLibreFlavor
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * The documented antimeridian and repeated-world contract, pinned on a real map. See
 * [MapVisibleAreaTest] for the visible-bounds half of the contract.
 */
class AntimeridianContractTest {

  /**
   * `screenLocationFromPosition` projects the world copy nearest the camera: longitudes equivalent
   * modulo 360° describe one screen location, which round-trips through
   * `positionFromScreenLocation`.
   */
  @Test
  fun equivalent_longitudes_project_to_the_copy_nearest_the_camera(): MapTestResult = runMapTest {
    val extent = MapExtent.fromLogical(width = 1600, height = 512, scaleFactor = 1.0)
    createMapFixture(extent).use {
      it.loadStyle(BaseStyle.Empty)
      it.awaitMapReady()
      it.state.setCameraPosition(CameraPosition(target = Position(179.0, 0.0), zoom = 1.0))
      it.pumpUntil("the repeated-world camera to apply") {
        abs(it.session.getCameraPosition().zoom - 1.0) < 0.01 &&
          abs(it.session.getCameraPosition().target.longitude - 179.0) < 0.01
      }

      // At zoom 1 a world is 1024 dp wide; lon 190 is the visible copy of the -170 meridian.
      val expected = assertNotNull(it.state.screenLocationFromPosition(Position(190.0, 0.0)))
      assertEquals(831.2889, expected.x.value.toDouble(), 1e-3)
      for (lon in listOf(-170.0, 550.0)) {
        val offset = assertNotNull(it.state.screenLocationFromPosition(Position(lon, 0.0)))
        assertEquals(expected.x.value, offset.x.value, 1e-4f, "lon $lon should project like 190")
      }

      val roundTrip = assertNotNull(it.state.positionFromScreenLocation(expected))
      assertEquals(190.0, roundTrip.longitude, 1e-3)
    }
  }

  /**
   * The engines disagree on camera target read-back for an out-of-range longitude: MapLibre Native
   * wraps into ±180°, GL JS keeps the value as given. Both render the same map. This pins the
   * divergence so a future parity change updates the test deliberately.
   */
  @Test
  fun camera_target_readback_for_an_out_of_range_longitude(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Empty)
      it.awaitMapReady()
      it.state.setCameraPosition(CameraPosition(target = Position(539.5, 20.0), zoom = 3.0))
      it.pumpUntil("the out-of-range camera to apply") {
        abs(it.session.getCameraPosition().zoom - 3.0) < 0.01
      }
      it.settle()

      val target = it.session.getCameraPosition().target
      when (mapLibreFlavor) {
        MapLibreFlavor.NATIVE -> assertEquals(179.5, target.longitude, 0.01)
        MapLibreFlavor.GL_JS -> assertEquals(539.5, target.longitude, 0.01)
      }
      // Either way, the visible bounds stay continuous around the camera's world copy.
      val bounds = assertNotNull(it.state.getVisibleBounds())
      assertEquals(45.0, bounds.east - bounds.west, 1.0)
    }
  }

  /** Camera constraints accept an antimeridian-crossing region in both encodings. */
  @Test
  fun camera_constraints_accept_a_region_across_the_antimeridian(): MapTestResult = runMapTest {
    for (box in
      listOf(
        BoundingBox(west = 170.0, south = -60.0, east = -170.0, north = 60.0),
        BoundingBox(west = 170.0, south = -60.0, east = 190.0, north = 60.0),
      )) {
      createMapFixture().use {
        it.loadStyle(BaseStyle.Empty)
        it.awaitMapReady()
        it.session.setCameraConstraints(CameraConstraints(boundingBox = box))
        it.state.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 2.0))
        it.settle()

        // The camera is clamped into the allowed band, whose longitudes wrap to [170, 180] or
        // [-180, -170]. Which edge the engine picks is engine-defined.
        val lon = it.session.getCameraPosition().target.longitude
        val wrapped = if (lon < -180.0) lon + 360.0 else if (lon > 180.0) lon - 360.0 else lon
        assertTrue(
          wrapped >= 169.9 || wrapped <= -169.9,
          "the camera should be clamped into the band across the antimeridian, was $lon (box $box)",
        )
      }
    }
  }

  /**
   * Queried feature geometry is not normalized: a fill straddling the antimeridian comes back as
   * one piece with continuous longitudes past 180° and one with wrapped longitudes past -180°.
   */
  @Test
  fun queried_geometry_keeps_engine_coordinates_across_the_antimeridian(): MapTestResult =
    runMapTest {
      val extent = MapExtent.fromLogical(width = 1600, height = 512, scaleFactor = 1.0)
      createMapFixture(extent).use {
        it.loadStyle(BaseStyle.Json(STRADDLING_FILL_STYLE))
        it.awaitMapReady()
        it.state.setCameraPosition(CameraPosition(target = Position(180.0, 0.0), zoom = 0.0))
        it.pumpUntil("the style's features to become queryable") {
          it.state.queryRenderedFeatures(offset = DpOffset(800.dp, 256.dp)).isNotEmpty()
        }
        // The wrapped world copy renders from its own tile, which can land after the first hit.
        it.settle()

        val hits =
          it.state.queryRenderedFeatures(offset = DpOffset(800.dp, 256.dp)).mapNotNull { hit ->
            hit.geometry as? Polygon
          }
        assertEquals(2, hits.size, "the straddling fill should come back split in two, was $hits")
        assertTrue(
          hits.any { polygon -> polygon.coordinates[0].any { it.longitude > 180.0 } },
          "one piece should carry continuous longitudes past 180°, was $hits",
        )
        assertTrue(
          hits.any { polygon -> polygon.coordinates[0].any { it.longitude < -180.0 } },
          "one piece should carry wrapped longitudes past -180°, was $hits",
        )
      }
    }

  private companion object {
    /** A small fill straddling the antimeridian, lon 178..182. */
    val STRADDLING_FILL_STYLE =
      """
      {
        "version": 8,
        "name": "antimeridian-contract-test",
        "sources": {
          "test": {
            "type": "geojson",
            "data": {
              "type": "Feature",
              "properties": { "name": "straddle" },
              "geometry": {
                "type": "Polygon",
                "coordinates": [
                  [[178, -5], [182, -5], [182, 5], [178, 5], [178, -5]]
                ]
              }
            }
          }
        },
        "layers": [
          { "id": "test-fill", "type": "fill", "source": "test", "paint": { "fill-color": "#ff0000" } }
        ]
      }
      """
        .trimIndent()
  }
}
