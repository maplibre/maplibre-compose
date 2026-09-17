package org.maplibre.compose.layers

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.location.HeadingMeasurement
import org.maplibre.compose.location.HeadingReference
import org.maplibre.compose.location.LocationMeasurement
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.map.LocalViewport
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.rememberStyleComposition
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.meters

@OptIn(ExperimentalTestApi::class)
class LocationIndicatorCompositionTest {
  @Test
  fun nativeUpdatesWrapAndResetWithoutSourcesOrViewportReads() = runComposeUiTest {
    val style = RecordingStyleBinding()
    val reconciler = StyleReconciler()
    var location by mutableStateOf<Position?>(Position(179.0, 52.0))
    var bearing by mutableStateOf<Bearing?>(null)
    var viewportReads = 0
    setContent {
      val revision by
        rememberStyleComposition(
          maybeStyle = style,
          content = {
            CompositionLocalProvider(
              LocalViewport providesComputed
                {
                  viewportReads++
                  null
                }
            ) {
              LocationIndicatorLayer(
                id = "user",
                location = location,
                bearing = bearing,
                accuracyRadius = 20.meters,
                locationTransition = TransitionOptions(2.seconds),
              )
            }
          },
        )
      LaunchedEffect(revision) { revision?.let { reconciler.apply(style, it) } }
    }
    fun paint(name: String): JsonElement =
      style.layers.getValue("user").getValue("paint").jsonObject.getValue(name)
    waitForIdle()
    assertEquals(setOf("user"), style.layers.keys)
    assertTrue(style.sources.isEmpty())
    assertEquals(0, viewportReads)
    assertEquals(20.0, paint("accuracy-radius").jsonPrimitive.double)
    assertEquals(
      2000.0,
      paint("location-transition").jsonObject.getValue("duration").jsonPrimitive.double,
    )

    runOnIdle {
      location = Position(-179.0, 52.0)
      bearing = Bearing.North + 350.degrees
    }
    waitForIdle()
    assertEquals(181.0, paint("location").jsonArray[1].jsonPrimitive.double)
    assertEquals(
      0.0,
      paint("bearing-transition").jsonObject.getValue("duration").jsonPrimitive.double,
    )
    runOnIdle { bearing = Bearing.North + 10.degrees }
    waitForIdle()
    assertEquals(
      2000.0,
      paint("bearing-transition").jsonObject.getValue("duration").jsonPrimitive.double,
    )
    runOnIdle { location = null }
    waitForIdle()
    assertTrue(style.layers.isEmpty())
    runOnIdle { location = Position(-178.0, 52.0) }
    waitForIdle()
    assertEquals(-178.0, paint("location").jsonArray[1].jsonPrimitive.double)
    assertEquals(
      0.0,
      paint("bearing-transition").jsonObject.getValue("duration").jsonPrimitive.double,
    )
  }

  @Test
  fun stateOverloadSelectsTheMoreAccurateHeading() = runComposeUiTest {
    val style = RecordingStyleBinding()
    val reconciler = StyleReconciler()
    val state =
      LocationState().apply {
        lastLocation =
          LocationMeasurement(
            position = Position(11.0, 48.0),
            horizontalAccuracy = 12.meters,
            course = Bearing.North + 60.degrees,
            courseAccuracy = 30.degrees,
            measuredAt = Clock.System.now(),
          )
        lastHeading =
          HeadingMeasurement(
            bearing = Bearing.North + 90.degrees,
            reference = HeadingReference.TrueNorth,
            accuracy = 5.degrees,
            measuredAt = Clock.System.now(),
          )
      }
    setContent {
      val revision by
        rememberStyleComposition(
          maybeStyle = style,
          content = {
            LocationIndicatorLayer(id = "user", locationState = state)
          },
        )
      LaunchedEffect(revision) { revision?.let { reconciler.apply(style, it) } }
    }
    waitForIdle()
    val paint = style.layers.getValue("user").getValue("paint").jsonObject
    assertEquals(90.0, paint.getValue("bearing").jsonPrimitive.double)
    assertEquals(12.0, paint.getValue("accuracy-radius").jsonPrimitive.double)
  }

  @Test
  fun longitudeTargetsStayInTheNearestWorld() {
    assertEquals(-181.0, unwrapLongitude(179.0, -179.0))
    assertEquals(541.0, unwrapLongitude(-179.0, 539.0))
    assertEquals(-179.0, unwrapLongitude(-179.0, null))
  }
}
