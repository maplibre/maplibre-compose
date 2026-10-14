package org.maplibre.compose.interaction.internal

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.map.GestureTestFixture

@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class PlatformScaleCancellationTest {
  @Test
  fun scene_cancellation_stops_scale_momentum_and_allows_the_next_pinch() = runTest {
    GestureTestFixture().use { map ->
      FrameRecomposer(coroutineContext).use { recomposer ->
        CanvasLayersComposeScene(recomposer, size = IntSize(200, 200)).use { scene ->
          scene.setContent { GestureHost(map.target, InputConfiguration.Standard) }
          fun frame(millis: Long) {
            runCurrent()
            recomposer.performFrame(millis * 1_000_000)
            scene.measureAndLayout()
            runCurrent()
          }
          fun pinch(start: Long) {
            fun send(type: PointerEventType, time: Long, factor: Float = 1f) {
              scene.sendPointerEvent(
                eventType = type,
                position = Offset(100f, 100f),
                type = PointerType.Mouse,
                timeMillis = time,
                scaleGestureFactor = factor,
              )
            }
            send(PointerEventType.ScaleStart, start)
            repeat(4) { send(PointerEventType.ScaleChange, start + (it + 1) * 20, 1.02f) }
            send(PointerEventType.ScaleEnd, start + 80)
          }

          frame(0)
          pinch(0)
          assertEquals(4, map.target.scaleCalls.size)
          // Nucleus closes the scale stream before forwarding OS cancellation to Compose.
          scene.cancelPointerInput()
          frame(100)
          frame(700)
          assertEquals(4, map.target.scaleCalls.size)
          assertEquals(1, map.target.endedCount)

          pinch(800)
          assertEquals(8, map.target.scaleCalls.size)
          frame(900)
          frame(1500)
          assertTrue(map.target.scaleCalls.size > 8, "normal release must still have momentum")
          assertEquals(2, map.target.endedCount)
        }
      }
    }
  }
}
