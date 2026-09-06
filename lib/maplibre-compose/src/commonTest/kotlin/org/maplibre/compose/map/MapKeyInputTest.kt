package org.maplibre.compose.map

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class MapKeyInputTest {
  private val map = GestureTestFixture()

  @AfterTest fun closeMap() = map.close()

  @Test
  fun overlapping_keys_share_authority_and_components_rearm_only_after_their_last_release() =
    runTest {
      val panStarts = mutableListOf<CameraInputStart>()
      val zoomStarts = mutableListOf<CameraInputStart>()
      val options = MapInteractions {
        camera {
          pan { onStart { panStarts += it } }
          zoom { onStart { zoomStarts += it } }
        }
      }
      map.target.updateConfiguration(options)
      val focus =
        MapInputFocus {}
        .also {
          it.hasKeyBindings = true
          it.onFocusChanged(true)
          it.engage(false)
        }
      val input =
        MapKeyInput(
          map.target,
          { options },
          focus,
          GestureContinuation(backgroundScope),
          GestureIds(),
          backgroundScope,
          InteractionSubscriptions(options).keys,
        )
      input.configure(options.structuralKey)
      fun down(key: Key) = assertTrue(input.onSample(key, KeyEventType.KeyDown, emptySet(), 0))
      fun up(key: Key) = assertTrue(input.onSample(key, KeyEventType.KeyUp, emptySet(), 0))
      down(Key.DirectionLeft)
      down(Key.DirectionRight)
      down(Key.Plus)
      down(Key.DirectionLeft)
      assertEquals(1, map.target.startedCount)
      assertEquals(1, panStarts.size)
      assertEquals(panStarts.single().sessionId, zoomStarts.single().sessionId)
      up(Key.DirectionLeft)
      down(Key.DirectionRight)
      assertEquals(1, panStarts.size)
      up(Key.DirectionRight)
      down(Key.DirectionRight)
      assertEquals(2, panStarts.size)
      assertEquals(panStarts.first().sessionId, panStarts.last().sessionId)
      up(Key.DirectionRight)
      assertEquals(0, map.target.endedCount)
      up(Key.Plus)
      runCurrent()
      assertEquals(1, map.target.endedCount)
      assertFalse(input.onSample(Key.Plus, KeyEventType.KeyUp, emptySet(), 0))
    }

  @Test
  fun release_drains_the_latest_repeat_without_ending_on_an_older_cancelled_step() = runTest {
    val options = MapInteractions.Standard
    val target =
      object : GestureTarget by map.target {
        override suspend fun moveByAwaitingTransition(
          deltaX: Double,
          deltaY: Double,
          duration: Duration,
          gestureToken: GestureToken,
        ) {
          map.target.moveByAwaitingTransition(deltaX, deltaY, duration, gestureToken)
          delay(300)
        }
      }
    val focus =
      MapInputFocus {}
      .also {
        it.hasKeyBindings = true
        it.onFocusChanged(true)
        it.engage(false)
      }
    val input =
      MapKeyInput(
        target,
        { options },
        focus,
        GestureContinuation(backgroundScope),
        GestureIds(),
        backgroundScope,
        InteractionSubscriptions(options).keys,
      )
    input.configure(options.structuralKey)
    input.onSample(Key.DirectionRight, KeyEventType.KeyDown, emptySet(), 0)
    advanceTimeBy(100)
    input.onSample(Key.DirectionRight, KeyEventType.KeyDown, emptySet(), 100)
    input.onSample(Key.DirectionRight, KeyEventType.KeyUp, emptySet(), 100)
    runCurrent()
    assertEquals(0, map.target.endedCount)
    assertEquals(1, map.target.startedCount)
    advanceTimeBy(299)
    runCurrent()
    assertEquals(0, map.target.endedCount)
    advanceTimeBy(1)
    runCurrent()
    assertEquals(1, map.target.endedCount)
  }

  @Test
  fun structural_change_suppresses_every_held_mapping_until_release() = runTest {
    var options = MapInteractions.Standard
    map.target.updateConfiguration(options)
    val focus =
      MapInputFocus {}
      .also {
        it.hasKeyBindings = true
        it.onFocusChanged(true)
        it.engage(false)
      }
    val input =
      MapKeyInput(
        map.target,
        { options },
        focus,
        GestureContinuation(backgroundScope),
        GestureIds(),
        backgroundScope,
        InteractionSubscriptions(options).keys,
      )
    input.configure(options.structuralKey)
    input.onSample(Key.DirectionRight, KeyEventType.KeyDown, emptySet(), 0)
    options = MapInteractions { bindings { keys { panStep = androidx.compose.ui.unit.Dp(50f) } } }
    input.configure(options.structuralKey)
    assertTrue(input.onSample(Key.DirectionRight, KeyEventType.KeyDown, emptySet(), 0))
    assertEquals(1, map.target.moveCalls.size)
    assertTrue(input.onSample(Key.DirectionRight, KeyEventType.KeyUp, emptySet(), 0))
    assertTrue(input.onSample(Key.DirectionRight, KeyEventType.KeyDown, emptySet(), 0))
    assertEquals(2, map.target.moveCalls.size)
    assertEquals(-50f, map.target.moveCalls.last().x)
    input.onSample(Key.DirectionRight, KeyEventType.KeyUp, emptySet(), 0)
    runCurrent()
  }
}
