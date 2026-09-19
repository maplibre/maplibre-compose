package org.maplibre.compose.editing

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.editing.internal.mercatorMidpoint
import org.maplibre.compose.editing.internal.mercatorX
import org.maplibre.compose.editing.internal.mercatorY
import org.maplibre.compose.editing.internal.translatedInMercator
import org.maplibre.compose.editing.internal.wrapLongitude
import org.maplibre.spatialk.geojson.Position

class MercatorTest {
  @Test
  fun midpoint_sits_on_the_rendered_segment_at_high_latitude() {
    val a = pos(0.0, 60.0)
    val b = pos(10.0, 80.0)
    val mid = mercatorMidpoint(a, b)
    assertEquals(5.0, mid.longitude, 1e-9)
    assertTrue(
      abs(mercatorY(mid.latitude) - (mercatorY(a.latitude) + mercatorY(b.latitude)) / 2) < 1e-12
    )
    assertTrue(mid.latitude > 70.0)
  }

  @Test
  fun translation_round_trips_and_keeps_altitude() {
    val start = Position(10.0, 70.0, 5.0)
    val dx = mercatorX(12.0) - mercatorX(10.0)
    val dy = mercatorY(71.0) - mercatorY(70.0)
    val moved = start.translatedInMercator(dx, dy)
    assertEquals(12.0, moved.longitude, 1e-9)
    assertEquals(71.0, moved.latitude, 1e-9)
    assertEquals(5.0, moved.altitude)
    val back = moved.translatedInMercator(-dx, -dy)
    assertEquals(10.0, back.longitude, 1e-9)
    assertEquals(70.0, back.latitude, 1e-9)
  }

  @Test
  fun wrap_longitude_maps_into_range() {
    assertEquals(-179.0, wrapLongitude(181.0), 1e-12)
    assertEquals(179.0, wrapLongitude(-181.0), 1e-12)
    assertEquals(180.0, wrapLongitude(180.0), 1e-12)
    assertEquals(0.0, wrapLongitude(720.0), 1e-12)
  }
}
