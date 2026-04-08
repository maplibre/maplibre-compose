package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalForeignApi::class)
class LocationIosTest {
  @Test
  fun shouldConvertMapLibreLocationToClLocation() {
    val source =
      Location(
        position = Position(longitude = 16.37208, latitude = 48.20849, altitude = 180.0),
        accuracy = 3.0,
        bearing = 90.0,
        bearingAccuracy = 1.0,
        speed = 13.0,
        speedAccuracy = 0.5,
        timestamp = TimeSource.Monotonic.markNow(),
      )

    val converted = source.asClLocation()

    converted.coordinate.useContents {
      assertEquals(source.position.latitude, latitude)
      assertEquals(source.position.longitude, longitude)
    }
    assertEquals(source.position.altitude, converted.altitude)
    assertEquals(source.accuracy, converted.horizontalAccuracy)
    assertEquals(-1.0, converted.verticalAccuracy)
    assertEquals(source.bearing, converted.course)
    assertEquals(source.speed, converted.speed)
  }
}
