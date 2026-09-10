package org.maplibre.compose.location

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.map.LocalViewport
import org.maplibre.compose.map.MapSnapshotRequest
import org.maplibre.compose.map.viewportFor
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.rememberStyleComposition
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.meters

@OptIn(ExperimentalTestApi::class)
class LocationPuckTest {
  @Test
  fun onlyVisibleAccuracyCircleObservesViewport() = runComposeUiTest {
    val style = RecordingStyleBinding()
    val reconciler = StyleReconciler()
    var location by mutableStateOf<LocationMeasurement?>(null)
    var viewport by mutableStateOf(viewportFor(MapSnapshotRequest(100, 100)))
    var viewportReads = 0
    setContent {
      val revision by
        rememberStyleComposition(
          maybeStyle = style,
          content = {
            CompositionLocalProvider(
              LocalDensity provides Density(1f),
              LocalViewport providesComputed { viewport.also { viewportReads++ } },
            ) {
              LocationPuck(idPrefix = "user", location = location)
            }
          },
        )
      LaunchedEffect(revision) { revision?.let { reconciler.apply(style, it) } }
    }
    fun accuracyRadius(): JsonElement =
      style.layers.getValue("user-accuracy").getValue("paint").jsonObject.getValue("circle-radius")

    waitForIdle()
    assertEquals(0, viewportReads, "A puck without a fix must not observe the viewport")
    runOnIdle {
      location =
        LocationMeasurement(
          position = Position(13.0, 52.0),
          horizontalAccuracy = 10.meters,
          measuredAt = Clock.System.now(),
        )
      viewport = viewport.copy(metersPerDpAtTarget = 2.0)
    }
    waitForIdle()
    assertEquals(0, viewportReads, "A hidden accuracy circle must not observe the viewport")

    runOnIdle { location = location!!.copy(horizontalAccuracy = 100.meters) }
    waitForIdle()
    assertTrue(viewportReads > 0, "Showing the accuracy circle must start observing the viewport")
    val firstRadius = accuracyRadius()
    runOnIdle { viewport = viewport.copy(metersPerDpAtTarget = 4.0) }
    waitForIdle()
    assertTrue(firstRadius != accuracyRadius(), "A visible accuracy circle must track map scale")

    runOnIdle { location = location!!.copy(horizontalAccuracy = 10.meters) }
    waitForIdle()
    val readsWhileHidden = viewportReads
    runOnIdle { viewport = viewport.copy(metersPerDpAtTarget = 8.0) }
    waitForIdle()
    assertEquals(readsWhileHidden, viewportReads, "Hiding accuracy must stop viewport observation")
    runOnIdle { location = location!!.copy(horizontalAccuracy = 100.meters) }
    waitForIdle()
    assertTrue(
      viewportReads > readsWhileHidden,
      "Showing accuracy must resume viewport observation",
    )
  }

  @Test
  fun locationFeatureExposesStaleness() {
    val location =
      LocationMeasurement(
        position = Position(longitude = 13.0, latitude = 52.0),
        measuredAt = Clock.System.now(),
      )

    val feature =
      locationFeatures(
          LocationPuckMeasurement(
            location = location,
            measurementMark = null,
            bearing = null,
            bearingAccuracy = null,
          ),
          isOldLocation = true,
        )
        .features
        .single()

    assertEquals(true, feature.properties["isOldLocation"]?.jsonPrimitive?.boolean)
  }

  @Test
  fun courseAccuracyIsOnlyTheDefaultForTheCourseBearing() {
    val course = Bearing.North + 30.degrees
    val accuracy = 5.degrees
    val location =
      LocationMeasurement(
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
    val time = TestTimeSource()
    var measurementMark by mutableStateOf(time.markNow())
    var isOld = false
    setContent { isOld = rememberIsLocationOld(1.seconds, measurementMark) }

    mainClock.advanceTimeByFrame()
    waitForIdle()
    assertFalse(isOld)

    mainClock.advanceTimeBy(1_100)
    waitForIdle()
    assertTrue(isOld)

    time += 2.seconds
    measurementMark = time.markNow()
    mainClock.advanceTimeByFrame()
    waitForIdle()
    assertFalse(isOld)
  }

  @Test
  fun suppliedMonotonicMarkDeterminesLiveStaleness() = runComposeUiTest {
    val measurementMark = TestTimeSource().markNow() - 2.seconds
    var isOld = false

    setContent {
      isOld = rememberIsLocationOld(1.seconds, measurementMark)
    }

    waitForIdle()
    assertTrue(isOld)
  }
}
