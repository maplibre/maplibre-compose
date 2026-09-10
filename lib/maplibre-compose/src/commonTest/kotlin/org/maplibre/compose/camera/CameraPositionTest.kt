package org.maplibre.compose.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.maplibre.spatialk.geojson.Position

class CameraPositionTest {
  @Test
  fun roundTripsCameraThroughJson() {
    val expected =
      CameraPosition(
        bearing = 42.5,
        target = Position(longitude = -122.675, latitude = 45.521, altitude = 12.0),
        tilt = 30.0,
        zoom = 13.0,
      )

    val encoded = Json.encodeToString(expected)

    assertEquals(expected, Json.decodeFromString<CameraPosition>(encoded))
  }

  @Test
  fun restoresOmittedFieldsToConstructorDefaults() {
    val restored = Json.decodeFromString<CameraPosition>("{}")

    assertEquals(CameraPosition(), restored)
  }
}
