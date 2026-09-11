package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.map.GestureTestFixture

@OptIn(ExperimentalCoroutinesApi::class)
class MapKeyInputTest {
  private val map = GestureTestFixture()
  private val clock = BroadcastFrameClock()
  private val panStep = InputConfiguration.Standard.bindings.keys.panStep.value
  private val stepMillis = InputConfiguration.Standard.animationDuration.inWholeMilliseconds

  @AfterTest fun closeMap() = map.close()

  private fun TestScope.keyInput(
    options: () -> InputConfiguration = { InputConfiguration.Standard },
    target: CameraInputTarget = map.target,
  ): KeyInput {
    val focus =
      InputFocus {}
      .also {
        it.hasKeyBindings = true
        it.onFocusChanged(true)
        it.engage(false)
      }
    return KeyInput(
        target,
        options,
        focus,
        CoroutineScope(backgroundScope.coroutineContext + clock),
      )
      .also { it.configure(options().settings) }
  }

  private fun KeyInput.down(key: Key) =
    assertTrue(onSample(key, KeyEventType.KeyDown, emptySet()), "$key press was not consumed")

  private fun KeyInput.up(key: Key) =
    assertTrue(onSample(key, KeyEventType.KeyUp, emptySet()), "$key release was not consumed")

  /** Sends a frame at [millis] since the test began and runs whatever it dispatched. */
  private fun TestScope.frame(millis: Long) {
    runCurrent()
    clock.sendFrame(millis * 1_000_000)
    runCurrent()
  }

  private val pannedX: Float
    get() = map.target.moveCalls.sumOf { it.x.toDouble() }.toFloat()

  @Test
  fun overlapping_keys_share_authority_and_components_rearm_only_after_their_last_release() =
    runTest {
      var panStarts = 0
      var zoomStarts = 0
      val options = InputConfiguration {
        camera {
          pan { onStart { panStarts++ } }
          zoom { onStart { zoomStarts++ } }
        }
      }
      map.target.updateConfiguration(options)
      val input = keyInput({ options })
      input.down(Key.DirectionLeft)
      input.down(Key.DirectionUp)
      input.down(Key.Plus)
      input.down(Key.DirectionLeft)
      frame(0)
      frame(16)
      assertEquals(1, map.target.startedCount)
      assertEquals(1, panStarts)
      assertEquals(1, zoomStarts)
      input.up(Key.DirectionLeft)
      input.down(Key.DirectionUp)
      frame(32)
      assertEquals(1, panStarts)
      input.up(Key.DirectionUp)
      input.down(Key.DirectionUp)
      frame(48)
      frame(64)
      assertEquals(2, panStarts)
      input.up(Key.DirectionUp)
      assertEquals(0, map.target.endedCount)
      input.up(Key.Plus)
      runCurrent()
      assertEquals(1, map.target.endedCount)
      assertFalse(input.onSample(Key.Plus, KeyEventType.KeyUp, emptySet()))
    }

  @Test
  fun a_held_key_moves_every_frame_and_stops_when_released() = runTest {
    val input = keyInput()
    input.down(Key.DirectionRight)
    frame(0)
    val frames = 6
    repeat(frames) { frame(16L * (it + 1)) }
    val moves = map.target.moveCalls.toList()
    assertEquals(frames, moves.size)
    assertTrue(moves.all { it.x < 0f && it.y == 0f }, "$moves")
    assertTrue(moves.all { it.x == moves.first().x }, "uneven frames: $moves")
    input.up(Key.DirectionRight)
    runCurrent()
    assertEquals(1, map.target.endedCount)
    val settled = map.target.moveCalls.toList()
    frame(200)
    frame(400)
    assertEquals(settled, map.target.moveCalls)
  }

  @Test
  fun a_press_shorter_than_a_step_completes_the_step_and_a_longer_hold_stops_on_release() =
    runTest {
      val input = keyInput()
      input.down(Key.DirectionRight)
      frame(0)
      frame(16)
      frame(32)
      input.up(Key.DirectionRight)
      runCurrent()
      assertEquals(-panStep, pannedX, 1e-3f)
      assertEquals(1, map.target.endedCount)
      map.target.moveCalls.clear()

      input.down(Key.DirectionRight)
      frame(1000)
      frame(1000 + stepMillis)
      frame(1000 + 2 * stepMillis)
      assertEquals(-2 * panStep, pannedX, 1e-3f)
      input.up(Key.DirectionRight)
      runCurrent()
      assertEquals(-2 * panStep, pannedX, 1e-3f)
      assertEquals(2, map.target.endedCount)
    }

  @Test
  fun a_tap_moves_one_eased_step() = runTest {
    val input = keyInput()
    input.down(Key.DirectionRight)
    input.up(Key.DirectionRight)
    runCurrent()
    assertEquals(1, map.target.moveCalls.size)
    assertEquals(-panStep, pannedX, 1e-3f)
    assertEquals(1, map.target.endedCount)
  }

  @Test
  fun repeats_are_consumed_without_changing_the_motion() = runTest {
    val input = keyInput()
    input.down(Key.DirectionRight)
    frame(0)
    frame(16)
    input.down(Key.DirectionRight)
    input.down(Key.DirectionRight)
    frame(32)
    frame(48)
    val moves = map.target.moveCalls
    assertEquals(3, moves.size)
    assertTrue(moves.all { it.x == moves.first().x }, "$moves")
    input.up(Key.DirectionRight)
    runCurrent()
    assertEquals(-panStep, pannedX, 1e-3f)
  }

  @Test
  fun held_keys_combine_and_opposite_keys_cancel() = runTest {
    val input = keyInput()
    input.down(Key.DirectionRight)
    input.down(Key.DirectionUp)
    input.down(Key.Plus)
    frame(0)
    frame(16)
    val move = map.target.moveCalls.single()
    assertTrue(move.x < 0f && move.y > 0f, "$move")
    assertTrue(map.target.scaleCalls.single().scale > 1.0)
    input.down(Key.DirectionLeft)
    frame(32)
    frame(48)
    val last = map.target.moveCalls.last()
    assertEquals(0f, last.x)
    assertTrue(last.y > 0f)
    listOf(Key.DirectionRight, Key.DirectionUp, Key.Plus, Key.DirectionLeft).forEach {
      input.up(it)
    }
    runCurrent()
    assertEquals(1, map.target.endedCount)
  }

  @Test
  fun camera_takeover_stops_a_held_key_until_it_is_released() = runTest {
    val input = keyInput()
    input.down(Key.DirectionRight)
    frame(0)
    frame(16)
    val moved = map.target.moveCalls.size
    assertTrue(moved > 0)
    val newer = map.target.onGestureStarted()
    runCurrent()
    frame(32)
    frame(48)
    assertEquals(moved, map.target.moveCalls.size)
    assertTrue(input.onSample(Key.DirectionRight, KeyEventType.KeyDown, emptySet()))
    input.up(Key.DirectionRight)
    frame(64)
    assertEquals(moved, map.target.moveCalls.size)
    map.target.onGestureEnded(newer)
    input.down(Key.DirectionRight)
    input.up(Key.DirectionRight)
    runCurrent()
    assertEquals(moved + 1, map.target.moveCalls.size)
  }

  @Test
  fun a_zero_animation_duration_jumps_a_step_per_press_and_repeat() = runTest {
    val options = InputConfiguration { animationDuration = Duration.ZERO }
    val input = keyInput({ options })
    input.down(Key.DirectionRight)
    input.down(Key.DirectionRight)
    frame(0)
    frame(16)
    assertEquals(2, map.target.moveCalls.size)
    assertEquals(-2 * panStep, pannedX, 1e-3f)
    input.up(Key.DirectionRight)
    runCurrent()
    assertEquals(-2 * panStep, pannedX, 1e-3f)
    assertEquals(1, map.target.endedCount)
  }

  @Test
  fun release_drains_the_remaining_step_before_ending_the_session() = runTest {
    val steps = mutableListOf<CompletableDeferred<Unit>>()
    val target =
      object : CameraInputTarget by map.target {
        override suspend fun moveByAwaitingTransition(
          deltaX: Double,
          deltaY: Double,
          duration: Duration,
          gestureToken: CameraInputToken,
        ) {
          map.target.moveByAwaitingTransition(deltaX, deltaY, duration, gestureToken)
          CompletableDeferred<Unit>().also { steps += it }.await()
        }
      }
    val input = keyInput(target = target)
    input.down(Key.DirectionRight)
    frame(0)
    frame(16)
    input.up(Key.DirectionRight)
    runCurrent()
    assertEquals(0, map.target.endedCount)
    assertEquals(1, map.target.startedCount)
    steps.single().complete(Unit)
    runCurrent()
    assertEquals(1, map.target.endedCount)
  }

  @Test
  fun structural_change_suppresses_every_held_mapping_until_release() = runTest {
    var options = InputConfiguration.Standard
    map.target.updateConfiguration(options)
    val input = keyInput({ options })
    input.down(Key.DirectionRight)
    frame(0)
    frame(16)
    options = InputConfiguration {
      bindings { keys { panStep = androidx.compose.ui.unit.Dp(50f) } }
    }
    input.configure(options.settings)
    val moved = map.target.moveCalls.size
    assertTrue(input.onSample(Key.DirectionRight, KeyEventType.KeyDown, emptySet()))
    frame(32)
    assertEquals(moved, map.target.moveCalls.size)
    input.up(Key.DirectionRight)
    input.down(Key.DirectionRight)
    input.up(Key.DirectionRight)
    runCurrent()
    assertEquals(moved + 1, map.target.moveCalls.size)
    assertEquals(-50f, map.target.moveCalls.last().x)
  }
}
