package org.maplibre.compose.interaction.internal

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.ModifierMatch
import org.maplibre.compose.interaction.ScrollResponse
import org.maplibre.compose.map.GestureTestFixture

@OptIn(ExperimentalAtomicApi::class, ExperimentalTestApi::class)
class ScrollGestureInputTest {
  private val fixture = GestureTestFixture()

  @AfterTest fun closeMap() = fixture.close()

  @Test
  fun an_unmatched_contact_does_not_cancel_an_active_scroll_burst() {
    fixture.runRecognitionTest(
      options =
        MapInteractions(MapInteractions.None) {
          bindings {
            scroll {
              enabled = true
              mappings { otherwise(ScrollResponse.Zoom) }
            }
          }
        }
    ) { target ->
      mainClock.autoAdvance = false
      try {
        val map = mapNode()
        map.performMouseInput { scroll(-1f) }
        map.performTouchInput {
          down(center)
          up()
        }
        waitForIdle()
        assertEquals(0, target.endedCount)
        map.performMouseInput { scroll(-1f) }
        waitForIdle()
        assertEquals(1, target.startedCount)
        assertEquals(2, target.scaleCalls.size)
        mainClock.advanceTimeBy(SCROLL_HOLD_MILLIS + FRAME_MILLIS)
      } finally {
        mainClock.autoAdvance = true
      }
    }
  }

  @Test
  fun standard_scroll_zooms_for_fractional_whole_and_two_axis_deltas() =
    fixture.runRecognitionTest { target ->
      for (delta in listOf(Offset(0f, 0.25f), Offset(0f, 1f), Offset(1f, 2f))) {
        mapNode().performMouseInput { scroll(delta) }
        waitForIdle()
        assertTrue(target.scaleCalls.isNotEmpty())
        assertTrue(target.moveCalls.isEmpty())
        target.scaleCalls.clear()
      }
    }

  @Test
  fun scroll_zoom_sensitivity_scales_the_same_reported_distance() {
    var options by mutableStateOf(MapInteractions { bindings { scroll { zoomPerDp = 0.01 } } })
    fixture.runRecognitionTest(optionsProvider = { options }) { target ->
      val map = mapNode()
      map.performMouseInput { scroll(Offset(3f, -2f)) }
      waitForIdle()
      val baseline = kotlin.math.log2(target.scaleCalls.single().scale)
      assertTrue(baseline > 0.0)
      runOnIdle {
        options = MapInteractions { bindings { scroll { zoomPerDp = 0.02 } } }
      }
      map.performMouseInput { scroll(Offset(3f, -2f)) }
      waitForIdle()
      assertEquals(baseline * 2.0, kotlin.math.log2(target.scaleCalls.last().scale), 1e-6)
    }
  }

  @Test
  fun split_axis_scroll_keeps_panning_when_horizontal_events_add_shift() {
    fixture.runRecognitionTest(
      options =
        MapInteractions {
          bindings {
            scroll {
              mappings { otherwise(ScrollResponse.Pan) }
            }
          }
        }
    ) { target ->
      mainClock.autoAdvance = false
      val map = mapNode()
      try {
        map.requestFocus()
        map.performMouseInput { scroll(-0.25f) }
        map.performKeyInput { keyDown(Key.ShiftLeft) }
        map.performMouseInput { scroll(Offset(-1f, 0f)) }
        map.performKeyInput { keyUp(Key.ShiftLeft) }
        map.performMouseInput { scroll(-1f) }
        waitForIdle()
        assertTrue(target.moveCalls.any { it.x != 0f })
        assertTrue(target.moveCalls.any { it.y != 0f })
        assertTrue(target.scaleCalls.isEmpty())
        assertEquals(1, target.startedCount)
        mainClock.advanceTimeBy(SCROLL_HOLD_MILLIS + FRAME_MILLIS)
        waitForIdle()
        assertEquals(1, target.endedCount)
      } finally {
        mainClock.autoAdvance = true
      }
    }
  }

  @Test
  fun explicit_scroll_mappings_switch_between_pan_and_zoom_with_ctrl() =
    fixture.runRecognitionTest(
      options =
        MapInteractions {
          bindings {
            scroll {
              mappings {
                on(
                  modifiers = ModifierMatch.Containing(KeyModifier.Ctrl),
                  response = ScrollResponse.Zoom,
                )
                otherwise(ScrollResponse.Pan)
              }
            }
          }
        }
    ) { target ->
      mainClock.autoAdvance = false
      val map = mapNode()
      try {
        map.requestFocus()
        map.performMouseInput { scroll(-0.25f) }
        waitForIdle()
        assertTrue(target.moveCalls.isNotEmpty())
        assertTrue(target.scaleCalls.isEmpty())
        target.moveCalls.clear()
        map.performKeyInput { keyDown(Key.CtrlLeft) }
        map.performMouseInput { scroll(-1f) }
        waitForIdle()
        assertTrue(target.moveCalls.isEmpty())
        assertTrue(target.scaleCalls.isNotEmpty())
        target.scaleCalls.clear()
        map.performKeyInput { keyUp(Key.CtrlLeft) }
        map.performMouseInput { scroll(-1f) }
        waitForIdle()
        assertTrue(target.moveCalls.isNotEmpty())
        assertTrue(target.scaleCalls.isEmpty())
      } finally {
        mainClock.autoAdvance = true
      }
    }

  @Test
  fun scroll_zoom_uses_vertical_motion_and_leaves_horizontal_only_input_unclaimed() {
    var parentSawConsumed = false
    fixture.runRecognitionTest(
      options =
        MapInteractions { bindings { scroll { mappings { otherwise(ScrollResponse.Zoom) } } } },
      parentModifier =
        Modifier.pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent(PointerEventPass.Main)
              if (event.type == PointerEventType.Scroll) {
                parentSawConsumed = event.changes.all { it.isConsumed }
              }
            }
          }
        },
    ) { target ->
      mapNode().performMouseInput { scroll(Offset(2f, 0f)) }
      waitForIdle()
      assertFalse(parentSawConsumed)
      assertTrue(target.scaleCalls.isEmpty())
      assertTrue(target.moveCalls.isEmpty())
      mapNode().performMouseInput { scroll(Offset(2f, -1f)) }
      waitForIdle()
      assertTrue(parentSawConsumed)
      assertEquals(1, target.scaleCalls.size)
      assertTrue(
        target.scaleCalls.single().scale > 1.0,
        "horizontal movement reversed the vertical zoom direction",
      )
    }
  }

  @Test
  fun a_scroll_burst_keeps_one_token_and_appends_no_momentum() =
    fixture.runRecognitionTest { target ->
      mainClock.autoAdvance = false
      try {
        mapNode().performMouseInput {
          scroll(-1f)
          advanceEventTime(50)
          scroll(-1f)
        }
        waitForIdle()
        assertEquals(1, target.startedCount)
        assertEquals(0, target.endedCount)
        assertEquals(2, target.scaleCalls.size)
        mainClock.advanceTimeBy(SCROLL_HOLD_MILLIS + FRAME_MILLIS)
        waitForIdle()
        assertEquals(1, target.endedCount)
        assertEquals(2, target.scaleCalls.size)
      } finally {
        mainClock.autoAdvance = true
      }
    }

  @Test
  fun consuming_scroll_cancels_the_burst_and_the_next_event_can_start_a_new_one() {
    var intercept = false
    fixture.runRecognitionTest(
      parentModifier =
        Modifier.pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent(PointerEventPass.Initial)
              if (intercept && event.type == PointerEventType.Scroll)
                event.changes.forEach { it.consume() }
            }
          }
        }
    ) { target ->
      mainClock.autoAdvance = false
      try {
        mapNode().performMouseInput { scroll(-1f) }
        runOnIdle { intercept = true }
        mapNode().performMouseInput { scroll(-1f) }
        waitForIdle()
        assertEquals(1, target.scaleCalls.size)
        assertEquals(1, target.endedCount)
        runOnIdle { intercept = false }
        mapNode().performMouseInput { scroll(-1f) }
        waitForIdle()
        assertEquals(2, target.scaleCalls.size)
        assertEquals(2, target.startedCount)
      } finally {
        mainClock.autoAdvance = true
      }
    }
  }

  @Test
  fun a_hover_does_not_end_a_scroll_hold() = fixture.runRecognitionTest { target ->
    val map = mapNode()
    mainClock.autoAdvance = false
    try {
      map.performMouseInput { scroll(-1f) }
      mainClock.advanceTimeByFrame()
      waitForIdle()
      assertEquals(1, target.startedCount, "the wheel did not open a gesture")
      assertEquals(0, target.endedCount, "the move ended inside the scroll event that started it")

      map.performMouseInput { moveTo(center) }
      mainClock.advanceTimeByFrame()
      waitForIdle()
      assertEquals(0, target.endedCount, "a hover ended the scroll hold")

      mainClock.advanceTimeBy(SCROLL_HOLD_MILLIS + FRAME_MILLIS)
      waitUntil(timeoutMillis = TIMEOUT) { target.endedCount == 1 }
    } finally {
      mainClock.autoAdvance = true
    }
    assertTrue(target.scaleCalls.any { it.scale > 1.0 }, "an upward wheel did not zoom in")
  }

  @Test
  fun the_scroll_hold_is_as_long_as_its_option_says() =
    fixture.runRecognitionTest(
      options = MapInteractions { bindings { scroll { idleDuration = 600.milliseconds } } }
    ) { target ->
      mainClock.autoAdvance = false
      try {
        mapNode().performMouseInput { scroll(-1f) }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(0, target.endedCount)

        mainClock.advanceTimeBy(400)
        waitForIdle()
        assertEquals(0, target.endedCount, "a 400 ms gap ended a move held open for 600 ms")

        mainClock.advanceTimeBy(300)
        waitUntil(timeoutMillis = TIMEOUT) { target.endedCount == 1 }
      } finally {
        mainClock.autoAdvance = true
      }
    }
}
