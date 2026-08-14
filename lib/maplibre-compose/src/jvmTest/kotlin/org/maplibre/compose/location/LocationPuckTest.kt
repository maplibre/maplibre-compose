package org.maplibre.compose.location

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class LocationPuckTest {
  @Test
  fun locationFeatureExposesStaleness() {
    val location =
      Location(
        position = PositionWithAccuracy(Position(longitude = 13.0, latitude = 52.0), null),
        timestamp = TimeSource.Monotonic.markNow(),
      )

    val feature = locationFeatures(location, bearing = null, isOldLocation = true).features.single()

    assertEquals(true, feature.properties["isOldLocation"]?.jsonPrimitive?.boolean)
  }

  @Test
  fun unchangedLocationBecomesOldAndNewLocationStartsFresh() = runComposeUiTest {
    mainClock.autoAdvance = false
    var location by
      mutableStateOf(
        Location(
          position =
            PositionWithAccuracy(
              value = Position(longitude = 13.0, latitude = 52.0),
              accuracy = null,
            ),
          timestamp = TimeSource.Monotonic.markNow(),
        )
      )
    var isOld = false
    setContent { isOld = rememberIsLocationOld(location, 1.seconds) }

    mainClock.advanceTimeByFrame()
    waitForIdle()
    assertFalse(isOld)

    mainClock.advanceTimeBy(1_100)
    waitForIdle()
    assertTrue(isOld)

    location = location.copy(timestamp = TimeSource.Monotonic.markNow())
    mainClock.advanceTimeByFrame()
    waitForIdle()
    assertFalse(isOld)
  }
}
