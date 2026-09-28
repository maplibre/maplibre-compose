package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.meters

class LocationMeasurementTest {
  @Test
  fun rejectsAccuracyWithoutItsMeasurement() {
    val position = Position(longitude = 13.0, latitude = 52.0)
    val at = Instant.parse("2026-08-28T12:34:56Z")
    assertFailsWith<IllegalArgumentException> {
      LocationMeasurement(position, altitudeAccuracy = 5.0.meters, measuredAt = at)
    }
    assertFailsWith<IllegalArgumentException> {
      LocationMeasurement(position, distancePerSecondAccuracy = 0.5.meters, measuredAt = at)
    }
    assertFailsWith<IllegalArgumentException> {
      LocationMeasurement(position, courseAccuracy = 4.0.degrees, measuredAt = at)
    }
  }

  @Test
  fun measurementsArePublicSerializableTypes() {
    assertEquals(
      "org.maplibre.compose.location.LocationMeasurement",
      LocationMeasurement.serializer().descriptor.serialName,
    )
    assertEquals(
      "org.maplibre.compose.location.HeadingMeasurement",
      HeadingMeasurement.serializer().descriptor.serialName,
    )
  }
}
