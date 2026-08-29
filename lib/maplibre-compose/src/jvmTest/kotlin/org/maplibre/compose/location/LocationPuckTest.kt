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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees

@OptIn(ExperimentalTestApi::class)
class LocationPuckTest {
  @Test
  fun locationFeatureExposesStaleness() {
    val location =
      LocationFix(
        position = Position(longitude = 13.0, latitude = 52.0),
        measuredAt = Clock.System.now(),
      )

    val feature =
      locationFeatures(location, bearing = null, bearingAccuracy = null, isOldLocation = true)
        .features
        .single()

    assertEquals(true, feature.properties["isOldLocation"]?.jsonPrimitive?.boolean)
  }

  @Test
  fun courseAccuracyIsOnlyTheDefaultForTheCourseBearing() {
    val course = Bearing.North + 30.degrees
    val accuracy = 5.degrees
    val location =
      LocationFix(
        position = Position(longitude = 13.0, latitude = 52.0),
        course = course,
        courseAccuracy = accuracy,
        measuredAt = Clock.System.now(),
      )

    assertEquals(accuracy, defaultBearingAccuracy(location, course))
    assertEquals(null, defaultBearingAccuracy(location, Bearing.North + 90.degrees))
    assertEquals(null, defaultBearingAccuracy(location, null))
  }

  @Test
  fun measurementBecomesOldAndNewMeasurementStartsFresh() = runComposeUiTest {
    mainClock.autoAdvance = false
    var measurementMark by mutableStateOf(TimeSource.Monotonic.markNow())
    var isOld = false
    setContent { isOld = rememberIsLocationOld(1.seconds, measurementMark) }

    mainClock.advanceTimeByFrame()
    waitForIdle()
    assertFalse(isOld)

    mainClock.advanceTimeBy(1_100)
    waitForIdle()
    assertTrue(isOld)

    measurementMark = TimeSource.Monotonic.markNow()
    mainClock.advanceTimeByFrame()
    waitForIdle()
    assertFalse(isOld)
  }

  @Test
  fun suppliedMonotonicMarkDeterminesLiveStaleness() = runComposeUiTest {
    val location =
      LocationFix(
        position = Position(longitude = 13.0, latitude = 52.0),
        measuredAt = Clock.System.now(),
      )
    val measurementMark = TimeSource.Monotonic.markNow() - 2.seconds
    var isOld = false

    setContent {
      isOld = rememberIsLocationOld(1.seconds, measurementMark)
    }

    waitForIdle()
    assertTrue(isOld)
  }
}
