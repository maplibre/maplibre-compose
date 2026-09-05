package org.maplibre.compose.map

import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class PlatformTransformIntegrationTest {
  @Test
  fun platform_components_apply_once_and_end_after_their_queued_commands_drain(): MapTestResult =
    runMapTest {
      coroutineScope {
        createMapFixture().use { fixture ->
          fixture.loadStyle(BaseStyle.Empty)
          fixture.state.setCameraPosition(CameraPosition(zoom = 3.0))
          fixture.awaitMapReady()
          fixture.settle()
          val options = MapGestures { pinchZoom { anchor = GestureAnchor.CameraCenter } }
          val input =
            MapPlatformTransform(
              fixture.gestures,
              options,
              { options },
              GestureIds(),
              this,
              PlatformTransformRouting(),
              {},
            )
          try {
            val size = checkNotNull(fixture.state.viewport).size
            fun sample(time: Long) =
              GesturePointerSample(
                0,
                time,
                DpOffset(size.width / 2, size.height / 2),
                null,
                setOf(PointerType.Mouse),
                emptySet(),
                emptySet(),
              )
            assertTrue(input.onInput(PointerEventType.PanStart, sample(0)))
            assertTrue(input.onInput(PointerEventType.ScaleStart, sample(0)))
            input.onInput(PointerEventType.PanMove, sample(10), panDelta = DpOffset(25.dp, 0.dp))
            input.onInput(PointerEventType.ScaleChange, sample(10), scaleFactor = 2.0)
            input.onInput(PointerEventType.PanMove, sample(20), panDelta = DpOffset(25.dp, 0.dp))
            input.onInput(PointerEventType.ScaleChange, sample(20), scaleFactor = 2.0)
            input.onInput(PointerEventType.PanEnd, sample(30))
            input.onInput(PointerEventType.ScaleEnd, sample(30))
            fixture.pumpUntil("platform commands finish") {
              !fixture.state.isCameraMoving && fixture.state.cameraPosition.zoom > 4.9
            }
            fixture.settle()
            val final = fixture.state.cameraPosition
            assertEquals(5.0, final.zoom, 1e-5)
            assertTrue(final.target.longitude < 0.0)
            assertEquals(CameraMoveReason.GESTURE, fixture.state.cameraMoveReason)
            fixture.pump(frames = 5)
            fixture.settle()
            assertEquals(final, fixture.state.cameraPosition)
            assertFalse(fixture.state.isCameraMoving)
          } finally {
            input.cancel()
          }
        }
      }
    }
}
