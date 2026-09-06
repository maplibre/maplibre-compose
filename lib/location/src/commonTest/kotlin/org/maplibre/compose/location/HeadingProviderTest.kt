package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.maplibre.spatialk.units.Bearing

class HeadingProviderTest {
  @Test
  fun headingSerializesWithoutRuntimeState() {
    val expected =
      HeadingMeasurement(
        bearing = Bearing.East,
        reference = HeadingReference.MagneticNorth,
        accuracy = null,
        measuredAt = Instant.parse("2026-08-28T12:34:56Z"),
      )

    val encoded = Json.encodeToString(expected)

    assertEquals(expected, Json.decodeFromString<HeadingMeasurement>(encoded))
  }

  @Test
  fun headingRequestRejectsNegativeIntervals() {
    assertFailsWith<IllegalArgumentException> { HeadingRequest((-1).seconds) }
  }
}
