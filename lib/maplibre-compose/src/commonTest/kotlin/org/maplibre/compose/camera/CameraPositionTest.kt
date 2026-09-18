package org.maplibre.compose.camera

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.Position

class CameraPositionTest {
  @Test
  fun partial_updates_preserve_omitted_fields_through_serialization() {
    val update = CameraUpdate(zoom = 0.0, padding = DpPadding.Zero)
    val restored = Json.decodeFromString<CameraUpdate>(Json.encodeToString(update))
    val initial = CameraPosition(bearing = 90.0, zoom = 5.0, padding = DpPadding(top = 10.dp))
    assertEquals(initial.copy(zoom = 0.0, padding = DpPadding.Zero), restored.applyTo(initial))
    assertEquals(initial, initial.toCameraUpdate().applyTo(CameraPosition()))
    assertFailsWith<IllegalArgumentException> { CameraUpdate() }
    assertFailsWith<IllegalArgumentException> { CameraUpdate(zoom = Double.NaN) }
  }

  @Test
  fun roundTripsCameraThroughJson() {
    val expected =
      CameraPosition(
        bearing = 42.5,
        target = Position(longitude = -122.675, latitude = 45.521, altitude = 12.0),
        tilt = 30.0,
        zoom = 13.0,
        padding = DpPadding(left = 12.dp, top = 24.dp, right = 36.dp, bottom = 48.dp),
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
