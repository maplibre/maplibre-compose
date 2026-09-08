package org.maplibre.compose.map

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.internal.RecognizedMapInput

@OptIn(ExperimentalCoroutinesApi::class)
class RecognizedMapInputTest {
  private val map = GestureTestFixture()

  @AfterTest fun closeMap() = map.close()

  private fun input(scope: CoroutineScope, target: RecordingGestureTarget = map.target) =
    RecognizedMapInput(target, target::capture, { true }, { MapInteractions.Standard }, scope)

  @Test
  fun host_callbacks_preserve_logical_units_and_scale_and_dispatch_clicks() = runTest {
    val input = input(backgroundScope)
    input.pan(DpOffset(12.dp, (-7).dp))
    runCurrent()
    input.scale(1.5, null)
    runCurrent()
    input.scale(0.75, DpOffset(20.dp, 30.dp))
    runCurrent()
    input.click(DpOffset(2.dp, 3.dp))
    runCurrent()

    assertEquals(listOf(Offset(12f, -7f)), map.target.moveCalls)
    assertEquals(
      listOf(
        RecordingGestureTarget.ScaleCall(1.5, null),
        RecordingGestureTarget.ScaleCall(0.75, DpOffset(20.dp, 30.dp)),
      ),
      map.target.scaleCalls,
    )
    assertEquals(3, map.target.endedCount)
    assertEquals(1, map.target.clicks)
  }

  @Test
  fun cancelling_a_fling_revokes_queued_camera_commands_before_the_engine_drains() = runTest {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(org.maplibre.compose.style.BaseStyle.Empty)
    val target = RecordingGestureTarget(state, deferred = true)
    try {
      val clock = BroadcastFrameClock()
      val input = input(CoroutineScope(backgroundScope.coroutineContext + clock), target)
      input.fling(DpOffset(2100.dp, 0.dp))
      runCurrent()
      clock.sendFrame(0)
      runCurrent()
      clock.sendFrame(100_000_000)
      runCurrent()
      input.cancel()
      target.drain()
      assertTrue(target.moveCalls.isEmpty())
      assertEquals(1, target.endedCount)
      clock.sendFrame(1_000_000_000)
      runCurrent()
      target.drain()
      assertTrue(target.moveCalls.isEmpty())
    } finally {
      state.close()
      target.drain()
      runtime.close()
    }
  }

  @Test
  fun fling_produces_motion_then_finishes_without_more_frames() = runTest {
    val clock = BroadcastFrameClock()
    val input = input(CoroutineScope(backgroundScope.coroutineContext + clock))
    input.fling(DpOffset(2100.dp, 0.dp))
    runCurrent()
    clock.sendFrame(0)
    runCurrent()
    clock.sendFrame(100_000_000)
    runCurrent()
    assertTrue(map.target.moveCalls.any { it.x > 0f })
    assertEquals(0, map.target.endedCount)
    clock.sendFrame(1_000_000_000)
    runCurrent()
    assertEquals(1, map.target.endedCount)
    val completed = map.target.moveCalls.toList()
    clock.sendFrame(2_000_000_000)
    runCurrent()
    assertEquals(completed, map.target.moveCalls)
  }

  @Test
  fun detachment_discards_a_click_waiting_for_application_delivery() = runTest {
    val input = input(backgroundScope)
    input.click(DpOffset(2.dp, 3.dp))
    input.cancel()
    runCurrent()
    assertEquals(0, map.target.clicks)
  }

  @Test
  fun host_input_obeys_camera_permissions_and_start_callbacks() = runTest {
    var starts = 0
    map.target.updateConfiguration(
      MapInteractions {
        camera {
          pan { enabled = false }
          zoom { onStart { starts++ } }
        }
      }
    )
    val input = input(backgroundScope)
    input.pan(DpOffset(12.dp, 0.dp))
    runCurrent()
    input.scale(2.0, DpOffset(20.dp, 30.dp))
    runCurrent()
    assertTrue(map.target.moveCalls.isEmpty())
    assertEquals(listOf(RecordingGestureTarget.ScaleCall(2.0, null)), map.target.scaleCalls)
    assertEquals(1, starts)
  }

  @Test
  fun invalid_input_does_not_start_a_gesture() = runTest {
    val input = input(backgroundScope)
    assertFailsWith<IllegalArgumentException> { input.pan(DpOffset(Dp(Float.NaN), 0.dp)) }
    assertFailsWith<IllegalArgumentException> { input.scale(-1.0, null) }
    assertFailsWith<IllegalArgumentException> { input.scale(2.0, DpOffset.Unspecified) }
    assertFailsWith<IllegalArgumentException> {
      input.fling(DpOffset(0.dp, Dp(Float.POSITIVE_INFINITY)))
    }
    assertFailsWith<IllegalArgumentException> { input.click(DpOffset.Unspecified) }
    assertEquals(0, map.target.startedCount)
  }
}
