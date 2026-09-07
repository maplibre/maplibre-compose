package org.maplibre.compose.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.maplibre.spatialk.geojson.Position

class VisibleBoundsTest {

  @Test
  fun reversed_longitudes_are_rejected() {
    assertFailsWith<IllegalArgumentException> {
      VisibleBounds(southwest = Position(10.0, 0.0), northeast = Position(-10.0, 1.0))
    }
  }

  @Test
  fun reversed_latitudes_are_rejected() {
    assertFailsWith<IllegalArgumentException> {
      VisibleBounds(southwest = Position(0.0, 10.0), northeast = Position(10.0, -10.0))
    }
  }

  @Test
  fun a_box_inside_the_world_wraps_to_itself() {
    val bounds =
      VisibleBounds(southwest = Position(-100.0, -40.0), northeast = Position(100.0, 40.0))
    val box = bounds.toBoundingBox()
    assertEquals(-100.0, box.west)
    assertEquals(100.0, box.east)
    assertEquals(-40.0, box.south)
    assertEquals(40.0, box.north)
  }

  @Test
  fun an_antimeridian_crossing_wraps_to_the_geojson_encoding() {
    // Continuous 170..190 becomes the RFC 7946 encoding: east < west.
    val bounds =
      VisibleBounds(southwest = Position(170.0, -10.0), northeast = Position(190.0, 10.0))
    val box = bounds.toBoundingBox()
    assertEquals(170.0, box.west)
    assertEquals(-170.0, box.east)
  }

  @Test
  fun a_span_wider_than_the_world_wraps_to_the_whole_world() {
    val bounds =
      VisibleBounds(southwest = Position(-100.0, -10.0), northeast = Position(462.5, 10.0))
    val box = bounds.toBoundingBox()
    assertEquals(-180.0, box.west)
    assertEquals(180.0, box.east)
  }

  @Test
  fun meridian_edges_wrap_without_flipping_sides() {
    // A west bound on the meridian stays -180; an east bound on the meridian stays 180.
    val fromMeridian =
      VisibleBounds(southwest = Position(180.0, 0.0), northeast = Position(190.0, 1.0))
    assertEquals(-180.0, fromMeridian.toBoundingBox().west)
    assertEquals(-170.0, fromMeridian.toBoundingBox().east)

    val toMeridian =
      VisibleBounds(southwest = Position(170.0, 0.0), northeast = Position(180.0, 1.0))
    assertEquals(170.0, toMeridian.toBoundingBox().west)
    assertEquals(180.0, toMeridian.toBoundingBox().east)
  }

  @Test
  fun a_zero_width_bound_stays_zero_width() {
    // west == east interpolates to a meridian strip per RFC 7946 §5.1, never to the whole world.
    val strip = VisibleBounds(southwest = Position(100.0, 0.0), northeast = Position(100.0, 1.0))
    assertEquals(100.0, strip.toBoundingBox().west)
    assertEquals(100.0, strip.toBoundingBox().east)

    // A zero-width bound on the antimeridian wraps both edges to -180°, from any approach side.
    for (longitude in listOf(-180.0, 180.0, 540.0)) {
      val meridian =
        VisibleBounds(southwest = Position(longitude, 0.0), northeast = Position(longitude, 1.0))
      assertEquals(-180.0, meridian.toBoundingBox().west)
      assertEquals(-180.0, meridian.toBoundingBox().east)
    }
  }
}
