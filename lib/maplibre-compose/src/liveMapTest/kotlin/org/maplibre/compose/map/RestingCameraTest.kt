package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.sources.CustomVectorTileSource
import org.maplibre.compose.sources.CustomVectorTileSourceOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.style.systemAnimatorDurationScale
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.testing.skipMapTest
import org.maplibre.spatialk.geojson.Position

/**
 * Camera rest is independent of renderer idle. Pending tiles keep [MapEvent.Idle] away, which is
 * what a caller that waits on [MapState.isCameraMoving] and [MapState.viewport] relies on.
 */
class RestingCameraTest {

  @Test
  fun pending_tiles_leave_the_camera_at_rest_without_idle(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.startWithPendingTiles()
      fixture.pump(QuietFrames)

      assertFalse(fixture.state.isCameraMoving)
      assertNotNull(fixture.state.viewport)
      assertNoIdle(fixture)
    }
  }

  @Test
  fun a_flight_over_pending_tiles_reports_moving_until_it_lands(): MapTestResult = runMapTest {
    if (systemAnimatorDurationScale() == 0f) skipMapTest("System animations are disabled")
    coroutineScope {
      createMapFixture().use { fixture ->
        fixture.startWithPendingTiles()

        val flight =
          launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.state.animateCamera(
              Destination.toCameraUpdate(),
              CameraAnimation.Fly { duration = FlightDuration },
            )
          }
        fixture.pumpUntil("the flight to start") { fixture.state.isCameraMoving }
        while (!flight.isCompleted) {
          assertTrue(fixture.state.isCameraMoving, "camera reported rest mid-flight")
          fixture.pump(frames = 1)
        }

        assertFalse(fixture.state.isCameraMoving)
        val landed = fixture.state.cameraPosition
        assertNear(Destination.zoom, landed.zoom, "landed zoom")
        assertNear(Destination.center.latitude, landed.center.latitude, "landed latitude")
        assertNear(Destination.center.longitude, landed.center.longitude, "landed longitude")
        assertNoIdle(fixture)
      }
    }
  }

  private suspend fun MapFixture.startWithPendingTiles() {
    loadStyle(PendingTileStyle)
    val style = checkNotNull(style)
    val requested = PendingTileRequest()
    val source =
      CustomVectorTileSource(
        SourceId,
        CustomVectorTileSourceOptions {
          minZoom = 0
          maxZoom = 0
        },
      ) {
        requested.started = true
        awaitCancellation()
      }
    style.install(source)
    val layer = TestLayer(LayerId, "circle", source)
    layer.sourceLayer = SourceLayer
    style.install(layer)
    pumpUntil("the pending tile request to start") { requested.started }
    awaitMapReady()
    engineEvents.clear()
  }

  private class PendingTileRequest {
    @Volatile var started = false
  }

  private companion object {
    const val SourceId = "pending-tiles"
    const val SourceLayer = "points"
    const val LayerId = "pending-circles"
    const val QuietFrames = 30

    val FlightDuration = 500.milliseconds

    val Destination = CameraPosition(center = Position(10.0, 10.0), zoom = 4.0)

    val PendingTileStyle =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "name": "resting-camera",
          "sources": {},
          "layers": [
            { "id": "bg", "type": "background", "paint": { "background-color": "#000000" } }
          ]
        }
        """
          .trimIndent(),
      )

    fun assertNoIdle(fixture: MapFixture) {
      assertTrue(
        fixture.engineEvents.none { it is MapEvent.Idle },
        "idle arrived while tiles were pending: ${fixture.engineEvents}",
      )
    }

    fun assertNear(expected: Double, actual: Double, message: String) {
      assertTrue(abs(expected - actual) < 0.01, "$message (expected $expected, was $actual)")
    }
  }
}
