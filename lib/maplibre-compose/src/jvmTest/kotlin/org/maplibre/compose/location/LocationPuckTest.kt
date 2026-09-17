package org.maplibre.compose.location

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
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
  fun bearingLayersRotateTogetherWithoutSubmittingGeometryEachFrame() = runComposeUiTest {
    val style = RecordingStyleBinding()
    val reconciler = StyleReconciler()
    val location =
      LocationMeasurement(position = Position(13.0, 52.0), measuredAt = Clock.System.now())
    var bearing by mutableStateOf<Bearing?>(Bearing.North + 10.degrees)
    var animation by
      mutableStateOf<LocationPuckAnimation?>(
        LocationPuckAnimation(bearing = tween(1000, easing = LinearEasing))
      )
    setContent {
      val revision by
        rememberStyleComposition(
          maybeStyle = style,
          content = {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
              LocationPuck(
                idPrefix = "user",
                location = location,
                bearing = bearing,
                bearingAccuracy = 5.degrees,
                animation = animation,
              )
            }
          },
        )
      LaunchedEffect(revision) { revision?.let { reconciler.apply(style, it) } }
    }
    fun rotation(layer: String): Float =
      style.layers
        .getValue(layer)
        .getValue("layout")
        .jsonObject
        .getValue("icon-rotate")
        .jsonPrimitive
        .float
    waitForIdle()
    assertEquals(55f, rotation("user-bearing"))
    mainClock.autoAdvance = false
    bearing = Bearing.North + 100.degrees
    mainClock.advanceTimeBy(160)
    waitForIdle()
    val firstRotation = rotation("user-bearing")
    assertTrue(firstRotation in 60f..90f)
    val submissions = style.installedGeoJson.values.sumOf { it.size }
    mainClock.advanceTimeBy(160)
    waitForIdle()
    val secondRotation = rotation("user-bearing")
    assertTrue(secondRotation > firstRotation)
    assertEquals(secondRotation - 140f, rotation("user-bearingAccuracy"), 0.01f)
    assertEquals(submissions, style.installedGeoJson.values.sumOf { it.size })
    animation = null
    bearing = Bearing.North + 120.degrees
    mainClock.advanceTimeBy(64)
    waitForIdle()
    assertEquals(165f, rotation("user-bearing"))
    animation = LocationPuckAnimation(bearing = null)
    bearing = Bearing.North + 30.degrees
    mainClock.advanceTimeBy(64)
    waitForIdle()
    assertEquals(75f, rotation("user-bearing"))
    bearing = null
    mainClock.advanceTimeBy(64)
    waitForIdle()
    assertEquals(
      "none",
      style.layers
        .getValue("user-bearing")
        .getValue("layout")
        .jsonObject
        .getValue("visibility")
        .jsonPrimitive
        .content,
    )
    assertFalse("user-bearingAccuracy" in style.layers)
  }

  @Test
  fun continuousRotationRebasesWithoutChangingOrientation() = runComposeUiTest {
    mainClock.autoAdvance = false
    var bearing by mutableStateOf(Bearing.North)
    var rendered = 0f
    setContent { rendered = animatePuckBearing(bearing, tween(64, easing = LinearEasing))!! }
    waitForIdle()
    repeat(30) { turn ->
      val target = (turn + 1) * 150
      bearing = Bearing.North + target.degrees
      mainClock.advanceTimeBy(128)
      waitForIdle()
      assertEquals((target % 360).toFloat(), (rendered % 360f + 360f) % 360f, 0.01f)
      assertTrue(abs(rendered) < 3800f, "The animation must bound its Float magnitude")
    }
  }

  @Test
  fun bearingCrossesNorthAlongTheShortArcInBothDirections() = runComposeUiTest {
    mainClock.autoAdvance = false
    var bearing by mutableStateOf(Bearing.North + 350.degrees)
    var forward = 0f
    var reverse = 0f
    setContent {
      forward = animatePuckBearing(bearing, tween(320, easing = LinearEasing))!!
      reverse =
        animatePuckBearing(
          Bearing.North - (bearing - Bearing.North),
          tween(320, easing = LinearEasing),
        )!!
    }
    waitForIdle()
    val initialForward = forward
    val initialReverse = reverse
    bearing = Bearing.North + 10.degrees
    mainClock.advanceTimeBy(160)
    waitForIdle()
    assertTrue(forward - initialForward in 5f..15f)
    assertTrue(reverse - initialReverse in -15f..-5f)
    mainClock.advanceTimeBy(400)
    waitForIdle()
    assertEquals(20f, forward - initialForward, 0.01f)
    assertEquals(-20f, reverse - initialReverse, 0.01f)
  }

  @Test
  fun bearingRetargetPreservesMotionInsteadOfRestartingAtRest() = runComposeUiTest {
    mainClock.autoAdvance = false
    var referenceTarget by mutableStateOf(Bearing.North)
    var interruptedTarget by mutableStateOf(Bearing.North)
    var reference = 0f
    var interrupted = 0f
    val spec = spring<Float>(stiffness = 100f)
    setContent {
      reference = animatePuckBearing(referenceTarget, spec)!!
      interrupted = animatePuckBearing(interruptedTarget, spec)!!
    }
    waitForIdle()
    referenceTarget = Bearing.North + 100.degrees
    interruptedTarget = referenceTarget
    mainClock.advanceTimeBy(160)
    waitForIdle()
    assertTrue(reference in 20f..80f)
    assertEquals(reference, interrupted, 0.01f)
    interruptedTarget = Bearing.North + 101.degrees
    mainClock.advanceTimeBy(64)
    waitForIdle()
    assertTrue(
      abs(reference - interrupted) < 1f,
      "Retargeting lost velocity: $reference vs $interrupted",
    )
  }

  @Test
  fun bearingAbsenceAndBypassResetAnimationHistory() = runComposeUiTest {
    mainClock.autoAdvance = false
    var bearing by mutableStateOf<Bearing?>(null)
    var spec by mutableStateOf<FiniteAnimationSpec<Float>?>(tween(1000))
    var rendered: Float? = null
    setContent { rendered = animatePuckBearing(bearing, spec) }
    waitForIdle()
    assertEquals(null, rendered)
    bearing = Bearing.North + 30.degrees
    mainClock.advanceTimeByFrame()
    waitForIdle()
    assertEquals(30f, rendered)
    bearing = Bearing.North + 90.degrees
    mainClock.advanceTimeBy(160)
    waitForIdle()
    assertTrue(rendered!! in 30f..60f)
    bearing = null
    mainClock.advanceTimeByFrame()
    waitForIdle()
    assertEquals(null, rendered)
    bearing = Bearing.North + 120.degrees
    mainClock.advanceTimeByFrame()
    waitForIdle()
    assertEquals(120f, rendered)
    spec = null
    bearing = Bearing.North + 60.degrees
    mainClock.advanceTimeByFrame()
    waitForIdle()
    assertEquals(60f, rendered)
    bearing = Bearing.North + 80.degrees
    mainClock.advanceTimeByFrame()
    waitForIdle()
    assertEquals(80f, rendered)
    spec = tween(1000)
    mainClock.advanceTimeByFrame()
    waitForIdle()
    assertEquals(80f, rendered)
  }

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
