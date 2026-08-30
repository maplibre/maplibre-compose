package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.meters

class LocationMeasurementTest {
  @Test
  fun rejectsAccuracyWithoutItsMeasurement() {
    val measuredAt = Instant.parse("2026-08-28T12:34:56Z")

    assertFailsWith<IllegalArgumentException> {
      LocationMeasurement(
        position = Position(longitude = 13.0, latitude = 52.0),
        altitudeAccuracy = 5.0.meters,
        measuredAt = measuredAt,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      LocationMeasurement(
        position = Position(longitude = 13.0, latitude = 52.0),
        speedAccuracy = 0.5.meters,
        measuredAt = measuredAt,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      LocationMeasurement(
        position = Position(longitude = 13.0, latitude = 52.0),
        courseAccuracy = 4.0.degrees,
        measuredAt = measuredAt,
      )
    }
  }

  @Test
  fun serializesMeasurementsWithUnknownAccuracyAndStableTime() {
    val expected =
      LocationMeasurement(
        position = Position(longitude = 13.0, latitude = 52.0, altitude = 42.0),
        horizontalAccuracy = null,
        altitudeAccuracy = null,
        speed = 3.0.meters,
        speedAccuracy = null,
        course = Bearing.North + 90.0.degrees,
        courseAccuracy = null,
        measuredAt = Instant.parse("2026-08-28T12:34:56Z"),
      )

    val encoded = Json.encodeToString(expected)

    assertEquals(expected, Json.decodeFromString<LocationMeasurement>(encoded))
    assertEquals(false, encoded.contains("TimeMark"))
  }
}
