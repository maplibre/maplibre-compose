package org.maplibre.compose.map

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleLayer
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Position

class MapHoverIntegrationTest {
  @Test
  fun a_stationary_pointer_exits_when_the_camera_moves_its_feature_away(): MapTestResult =
    runMapTest {
      coroutineScope {
        createMapFixture().use { fixture ->
          fixture.loadStyle(BaseStyle.Json(POINT_STYLE))
          fixture.state.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 3.0))
          fixture.awaitMapReady()
          fixture.settle()
          val entered = CompletableDeferred<Unit>()
          val exited = CompletableDeferred<Unit>()
          var enterCount = 0
          var exitCount = 0
          val style = checkNotNull(fixture.style)
          val layer = style.getLayers().single { it.id == "point" }
          val node =
            DesiredStyleLayer(
              layer.definition(),
              Anchor.Top,
              null,
              null,
              registration = Any(),
              onHover = {
                when (it) {
                  is HoverEvent.Enter -> {
                    enterCount++
                    entered.complete(Unit)
                  }
                  is HoverEvent.Exit -> {
                    exitCount++
                    exited.complete(Unit)
                  }
                  is HoverEvent.Move -> Unit
                }
              },
            )
          val revision =
            mutableStateOf<DesiredStyleRevision?>(
              DesiredStyleRevision(emptyList(), listOf(node), emptyList())
            )
          val dispatcher =
            MapInteractionDispatcher(
              fixture.state,
              mutableStateOf<State<DesiredStyleRevision?>>(revision),
              mutableStateOf(style),
              mutableStateOf(MapGestures.Standard),
            )
          val work = Job(coroutineContext[Job])
          val scope = CoroutineScope(coroutineContext + work)
          val hover =
            MapHoverGesture(
              scope,
              fixture.gestures,
              dispatcher,
              { MapGestures.Standard },
              GestureIds(),
              Density(1f),
              { delay(16) },
            )
          try {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
              fixture.state.events.collect { event ->
                if (event is MapEvent.FrameRendered || event == MapEvent.Idle)
                  dispatcher.presentationChanged(fixture.session)
                // This fixture has no UI host to apply global snapshot writes.
                Snapshot.sendApplyNotifications()
              }
            }
            val size = checkNotNull(fixture.state.viewport).size
            hover.move(
              GesturePointerSample(
                0,
                1,
                DpOffset(size.width / 2, size.height / 2),
                null,
                setOf(PointerType.Mouse),
                emptySet(),
                emptySet(),
              )
            )
            fixture.awaitWhileRendering("hover enters rendered point") { entered.await() }
            fixture.settle()
            assertFalse(fixture.state.isCameraMoving)
            fixture.state.setCameraPosition(
              CameraPosition(target = Position(80.0, 0.0), zoom = 3.0)
            )
            fixture.awaitWhileRendering("stationary hover exits after camera change") {
              exited.await()
            }
            hover.exit()
            fixture.settle()
            assertEquals(1, enterCount)
            assertEquals(1, exitCount)
          } finally {
            hover.exit()
            work.cancel()
            work.join()
          }
        }
      }
    }

  companion object {
    private val POINT_STYLE =
      """
      {
        "version": 8,
        "sources": {
          "point": { "type": "geojson", "data": {
            "type": "Feature", "geometry": { "type": "Point", "coordinates": [0, 0] }, "properties": {}
          } }
        },
        "layers": [{ "id": "point", "type": "circle", "source": "point", "paint": { "circle-radius": 32, "circle-color": "red" } }]
      }
      """
        .trimIndent()
  }
}
