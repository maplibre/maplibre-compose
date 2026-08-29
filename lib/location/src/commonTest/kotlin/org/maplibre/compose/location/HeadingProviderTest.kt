package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees

class HeadingProviderTest {
  @Test
  fun eachCollectorOwnsAHeadingRequest() = runTest {
    var starts = 0
    var stops = 0
    val expected =
      Heading(
        bearing = Bearing.East,
        accuracy = 3.0.degrees,
        measuredAt = Instant.parse("2026-08-28T12:34:56Z"),
      )
    val provider =
      object : HeadingProvider {
        override fun updates(request: HeadingRequest): Flow<Heading> = flow {
          starts++
          try {
            emit(expected)
            awaitCancellation()
          } finally {
            stops++
          }
        }
      }

    assertEquals(expected, provider.updates(HeadingRequest()).first())
    assertEquals(expected, provider.updates(HeadingRequest()).first())
    assertEquals(2, starts)
    assertEquals(2, stops)
  }

  @Test
  fun headingSerializesWithoutRuntimeState() {
    val expected =
      Heading(
        bearing = Bearing.East,
        accuracy = null,
        measuredAt = Instant.parse("2026-08-28T12:34:56Z"),
      )

    val encoded = Json.encodeToString(expected)

    assertEquals(expected, Json.decodeFromString<Heading>(encoded))
    assertEquals(false, encoded.contains("TimeMark"))
  }

  @Test
  fun headingRequestRejectsNegativeIntervals() {
    assertFailsWith<IllegalArgumentException> { HeadingRequest((-1).seconds) }
  }
}
