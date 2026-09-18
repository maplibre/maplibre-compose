package org.maplibre.compose.demoapp

import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.demoapp.demos.DefaultLocationEngine
import org.maplibre.compose.location.LocationEvent
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.inDegrees
import org.maplibre.spatialk.units.extensions.inMeters

@OptIn(ExperimentalCoroutinesApi::class)
class MockLocationEngineTest {
  @Test
  fun editsReachActiveProvidersWithoutCameraFollowing() = runTest {
    val ui = DemoLocationUi()
    ui.selectEngine(ui.mockEngine, Position(12.0, 34.0))
    assertFalse(ui.isFollowing)
    assertTrue(ui.isTracking)
    val locations = mutableListOf<LocationEvent>()
    val headings = mutableListOf<org.maplibre.compose.location.HeadingMeasurement>()
    backgroundScope.launch { ui.mockEngine.createLocationProvider().updates().toList(locations) }
    backgroundScope.launch { ui.mockEngine.createHeadingProvider().updates().toList(headings) }
    runCurrent()
    assertEquals(
      Position(12.0, 34.0),
      assertIs<LocationEvent.Update>(locations.last()).measurement.position,
    )
    Snapshot.withMutableSnapshot {
      ui.mockEngine.sample =
        ui.mockEngine.sample.copy(
          position = Position(13.0, 35.0),
          bearing = 270f,
          positionAccuracy = 80f,
          bearingAccuracy = 45f,
        )
    }
    runCurrent()
    val fix = assertIs<LocationEvent.Update>(locations.last())
    assertEquals(Position(13.0, 35.0), fix.measurement.position)
    assertEquals(80.0, fix.measurement.horizontalAccuracy?.inMeters)
    assertEquals(270.0, (headings.last().bearing - Bearing.North).inDegrees)
    assertEquals(45.0, headings.last().accuracy?.inDegrees)
    assertNotNull(fix.measurementMark)
    assertFalse(ui.isFollowing)
  }

  @Test
  fun unknownAccuracyAndMissingHeadingDoNotFabricateMeasurements() = runTest {
    val engine = MockLocationEngine()
    engine.sample = engine.sample.copy(positionAccuracyKnown = false, bearingAccuracyKnown = false)
    val fix = assertIs<LocationEvent.Update>(engine.createLocationProvider().updates().first())
    assertNull(fix.measurement.horizontalAccuracy)
    assertNull(fix.measurement.course)
    assertNull(engine.createHeadingProvider().updates().first().accuracy)
    engine.sample = engine.sample.copy(headingAvailable = false)
    assertTrue(engine.createHeadingProvider().updates().toList().isEmpty())
  }

  @Test
  fun placementIsOneShotAndCancelsWhenSwitchingToDevice() {
    val ui = DemoLocationUi()
    val initial = Position(1.0, 2.0)
    val placed = Position(3.0, 4.0)
    ui.selectEngine(ui.mockEngine, initial)
    ui.followMode = DemoFollowMode.Heading
    ui.beginMockPlacement()
    assertFalse(ui.isFollowing)
    assertFalse(ui.placeMockLocation(null))
    assertTrue(ui.placingMockLocation)
    assertTrue(ui.placeMockLocation(placed))
    assertFalse(ui.placeMockLocation(initial))
    assertEquals(placed, ui.mockEngine.sample.position)
    ui.beginMockPlacement()
    ui.selectEngine(DefaultLocationEngine, initial)
    assertFalse(ui.placingMockLocation)
    assertFalse(ui.isTracking)
    assertFalse(ui.placeMockLocation(initial))
    assertEquals(placed, ui.mockEngine.sample.position)
  }
}
