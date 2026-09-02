package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleComposition
import org.maplibre.spatialk.geojson.Position

class MapRuntimeTest {
  @Test
  fun runtime_closes_every_child_before_shared_resources() = runTest {
    lateinit var first: MapState
    lateinit var second: MapState
    var resourcesClosed = false
    val runtime = mapRuntimeForTest {
      assertTrue(first.isClosed)
      assertTrue(second.isClosed)
      resourcesClosed = true
    }
    first = runtime.createMapState(BaseStyle.Demo)
    second = runtime.createMapState(BaseStyle.Demo)

    runtime.close()

    assertTrue(first.isClosed)
    assertTrue(second.isClosed)
    assertFailsWith<MapRuntimeClosedException> { runtime.createMapState(BaseStyle.Demo) }
    runtime.awaitClosed()
    assertTrue(resourcesClosed)
  }

  @Test
  fun child_closure_does_not_close_its_runtime() = runTest {
    val runtime = mapRuntimeForTest()
    val first = runtime.createMapState(BaseStyle.Demo)

    first.close()
    first.awaitClosed()

    assertTrue(first.isClosed)
    assertFalse(runtime.isClosed)
    val second = runtime.createMapState(BaseStyle.Demo)
    assertNotSame(first, second)
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun independently_configured_runtimes_close_independently() = runTest {
    var firstResourcesClosed = false
    var secondResourcesClosed = false
    val first = mapRuntimeForTest { firstResourcesClosed = true }
    val second = mapRuntimeForTest { secondResourcesClosed = true }
    val secondState = second.createMapState(BaseStyle.Demo)

    first.close()
    first.awaitClosed()

    assertTrue(firstResourcesClosed)
    assertFalse(secondResourcesClosed)
    assertFalse(secondState.isClosed)
    second.createMapState(BaseStyle.Demo).close()
    second.close()
    second.awaitClosed()
    assertTrue(secondResourcesClosed)
  }

  @Test
  fun state_starts_with_the_requested_durable_values() {
    val runtime = mapRuntimeForTest()
    val camera =
      CameraPosition(
        bearing = 12.0,
        target = Position(longitude = 7.0, latitude = 8.0),
        tilt = 30.0,
        zoom = 9.0,
      )

    val state = runtime.createMapState(baseStyle = BaseStyle.Empty, initialCameraPosition = camera)

    assertTrue(state.cameraPosition == camera)
    assertTrue(state.style.baseStyle == BaseStyle.Empty)
    assertTrue(state.currentMapAttachment == null)
    state.close()
    runtime.close()
  }

  @Test
  fun await_closed_waits_for_shared_resource_cleanup() = runTest {
    val releaseResources = CompletableDeferred<Unit>()
    val runtime = mapRuntimeForTest { releaseResources.await() }

    runtime.close()
    val waiting = CompletableDeferred<Unit>()
    backgroundScope.launch {
      runtime.awaitClosed()
      waiting.complete(Unit)
    }

    testScheduler.runCurrent()
    assertFalse(waiting.isCompleted)
    releaseResources.complete(Unit)
    waiting.await()
  }

  @Test
  fun runtime_closes_snapshotter_children_before_shared_resources() = runTest {
    lateinit var snapshotter: MapSnapshotter
    var resourcesClosed = false
    val runtime = mapRuntimeForTest {
      resourcesClosed = true
    }
    snapshotter = runtime.createSnapshotter(BaseStyle.Empty, StyleComposition.Empty)

    runtime.close()

    assertFailsWith<MapRuntimeClosedException> {
      runtime.createSnapshotter(BaseStyle.Empty, StyleComposition.Empty)
    }
    assertFailsWith<MapSnapshotterClosedException> {
      snapshotter.capture(MapSnapshotRequest(1, 1))
    }
    runtime.awaitClosed()
    assertTrue(resourcesClosed)
  }
}
