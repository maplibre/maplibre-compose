package org.maplibre.compose.interaction.internal

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.interaction.ClickEvent
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.DragResponse
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.compose.interaction.TapResponse
import org.maplibre.compose.map.GestureTestFixture
import org.maplibre.compose.map.RecordingGestureTarget
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.mlnffi.runPlainComposeUiTest
import org.maplibre.compose.style.BaseStyle

@OptIn(ExperimentalAtomicApi::class, ExperimentalTestApi::class)
class ClickInputTest {
  private val fixture = GestureTestFixture()

  @AfterTest fun closeMap() = fixture.close()

  @Test
  fun consuming_a_double_tap_binding_suppresses_zoom_and_map_delivery() {
    val doubles = mutableListOf<ClickEvent>()
    fixture.runRecognitionTest(
      options =
        InputConfiguration {
          callbacks {
            doubleClick {
              onEvent {
                doubles += it
                ClickResult.Consume
              }
            }
          }
        }
    ) { target ->
      mapNode().performTouchInput {
        click(center)
        advanceEventTime(SECOND_TAP_GAP_MILLIS)
        click(center)
      }
      waitForIdle()
      assertEquals(1, doubles.size)
      assertEquals(0, target.clicks)
      assertTrue(target.scaleCalls.isEmpty())
      assertTrue(target.deliveredTapFamilies.isEmpty())
    }
  }

  @Test
  fun a_primary_click_does_not_suppress_secondary_click_when_long_press_is_disabled() {
    val events = mutableListOf<ClickEvent>()
    fixture.runRecognitionTest(
      options =
        InputConfiguration {
          bindings { longPress { enabled = false } }
          callbacks {
            longClick {
              onEvent {
                events += it
                ClickResult.Pass
              }
            }
          }
        }
    ) { target ->
      mapNode().performMouseInput {
        click(center)
        advanceEventTime(SECOND_TAP_GAP_MILLIS)
        click(center, MouseButton.Secondary)
      }
      waitForIdle()
      assertEquals(setOf(PointerButton.Secondary), events.single().buttons)
      assertEquals(1, target.longClicks)
      assertEquals(listOf(TapFamily.Tap, TapFamily.SecondaryClick), target.deliveredTapFamilies)
    }
  }

  @Test
  fun an_unused_double_tap_binding_does_not_delay_touch_clicks() {
    fixture.runRecognitionTest(
      options =
        InputConfiguration {
          bindings { doubleTap { mappings {} } }
          bindings { tapDrag { enabled = false } }
        }
    ) { target ->
      mainClock.autoAdvance = false
      try {
        mapNode().performTouchInput { click(center) }
        waitForIdle()
        assertEquals(1, target.clicks)
      } finally {
        mainClock.autoAdvance = true
      }
    }
  }

  @Test
  fun consuming_the_first_mouse_click_does_not_consume_the_later_double_click() {
    fixture.runRecognitionTest(
      options =
        InputConfiguration {
          callbacks { click { onEvent { ClickResult.Consume } } }
        }
    ) { target ->
      mapNode().performMouseInput { doubleClick(center) }
      waitForIdle()
      assertEquals(0, target.clicks)
      assertEquals(listOf(TapFamily.DoubleTap), target.deliveredTapFamilies)
      assertEquals(1, target.scaleCalls.size)
    }
  }

  @Test
  fun releasing_a_long_press_does_not_cancel_its_accepted_zoom() = runPlainComposeUiTest {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Empty)
    val target = RecordingGestureTarget(state, deferred = true)
    try {
      setContent {
        GestureHost(
          target,
          InputConfiguration(from = InputConfiguration.NoBindings) {
            bindings {
              longPress {
                enabled = true
                mappings { otherwise(TapResponse.ZoomIn) }
              }
            }
          },
        )
      }
      waitForIdle()
      mainClock.autoAdvance = false
      mapNode().performTouchInput { down(center) }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      assertEquals(1, target.startedCount, "the long press did not start its zoom")
      assertTrue(target.scaleCalls.isEmpty(), "engine execution should still be paused")
      mapNode().performTouchInput { up() }
      target.drain()
      waitForIdle()
      assertEquals(1, target.scaleCalls.size, "release discarded the long press's zoom")
    } finally {
      mainClock.autoAdvance = true
      state.close()
      target.drain()
      runtime.close()
    }
  }

  @Test
  fun an_unbound_click_preserves_touch_momentum_until_bindings_change() {
    var configuration by
      mutableStateOf(
        InputConfiguration(from = InputConfiguration.NoBindings) {
          bindings {
            drag {
              enabled = true
              pointerTypes = setOf(PointerType.Touch)
              mappings { otherwise(DragResponse.Pan) }
            }
          }
        }
      )
    fixture.runRecognitionTest(optionsProvider = { configuration }) { target ->
      mainClock.autoAdvance = false
      val map = mapNode()
      map.performTouchInput {
        down(center)
        repeat(6) { moveBy(Offset(20f, 0f), delayMillis = 16) }
        up()
      }
      val releasedMoves = target.moveCalls.size
      mainClock.advanceTimeBy(64)
      waitForIdle()
      assertTrue(target.moveCalls.size > releasedMoves, "the touch pan did not fling")
      map.performMouseInput { click(center) }
      val movesAfterClick = target.moveCalls.size
      mainClock.advanceTimeBy(64)
      waitForIdle()
      assertTrue(
        target.moveCalls.size > movesAfterClick,
        "an unbound mouse click stopped the fling",
      )
      runOnIdle {
        configuration =
          InputConfiguration(from = configuration) { bindings { drag { enabled = false } } }
      }
      mainClock.advanceTimeByFrame()
      waitForIdle()
      val movesAfterReconfiguration = target.moveCalls.size
      mainClock.advanceTimeBy(64)
      waitForIdle()
      assertEquals(movesAfterReconfiguration, target.moveCalls.size)
      mainClock.autoAdvance = true
    }
  }

  @Test
  fun a_parent_consuming_unclaimed_motion_in_main_cancels_click_and_long_press() =
    fixture.runRecognitionTest(
      parentModifier = Modifier.consumePointerEvents(PointerEventPass.Main, PointerEventType.Move)
    ) { target ->
      mapNode().performTouchInput {
        down(center)
        moveBy(Offset(1f, 0f))
      }
      mainClock.advanceTimeBy(1_000)
      mapNode().performTouchInput {
        moveBy(Offset(60f, 0f))
        up()
      }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      assertEquals(0, target.startedCount, "a cancelled contact restarted on a later Move")
      assertEquals(0, target.clicks)
      assertEquals(0, target.longClicks)
    }

  @Test
  fun a_consumed_release_does_not_click_or_launch_a_fling() =
    fixture.runRecognitionTest(
      parentModifier =
        Modifier.consumePointerEvents(PointerEventPass.Initial, PointerEventType.Release)
    ) { target ->
      mapNode().performTouchInput { click(center) }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      assertEquals(0, target.clicks)
      assertEquals(0, target.startedCount)
    }

  @Test
  fun a_press_that_jitters_within_the_slop_still_clicks() = fixture.runRecognitionTest { target ->
    mapNode().performMouseInput {
      moveTo(center)
      press()
      moveBy(Offset(1f, 0f))
      release()
    }
    awaitClicks(target, 1)
    assertEquals(0, target.moveCalls.size, "the jitter panned")
  }

  @Test
  fun a_press_past_the_slop_drags_instead_of_clicking() = fixture.runRecognitionTest { target ->
    mapNode().performMouseInput {
      moveTo(center)
      press()
      moveBy(Offset(60f, 0f))
      release()
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.moveCalls.isNotEmpty() }
    assertEquals(0, target.clicks, "the drag reported a click")
  }

  @Test
  fun increasing_drag_slop_does_not_expand_click_tolerance() =
    fixture.runRecognitionTest(
      options = InputConfiguration { bindings { drag { pan { mouseStartSlop = 100.dp } } } }
    ) { target ->
      mapNode().performMouseInput {
        moveTo(center)
        press()
        moveBy(Offset(60f, 0f))
        release()
      }
      waitForIdle()
      assertEquals(0, target.clicks)
      assertTrue(target.moveCalls.isEmpty())
    }

  @Test
  fun a_map_click_does_not_also_click_its_parent() {
    val parentClicks = AtomicInt(0)
    fixture.runRecognitionTest(parentOnClick = { parentClicks.incrementAndFetch() }) { target ->
      mapNode().performMouseInput { click(center) }
      awaitClicks(target, 1)
      waitForIdle()
      assertEquals(0, parentClicks.load())
    }
  }

  @Test
  fun a_map_long_click_does_not_also_long_click_its_parent() {
    val parentLongClicks = AtomicInt(0)
    fixture.runRecognitionTest(parentOnLongClick = { parentLongClicks.incrementAndFetch() }) {
      target ->
      val map = mapNode()
      map.performTouchInput { down(0, center) }
      mainClock.advanceTimeBy(1_000)
      waitUntil(timeoutMillis = TIMEOUT) { target.longClicks == 1 }
      map.performTouchInput { up(0) }
      waitForIdle()
      assertEquals(0, parentLongClicks.load())
    }
  }

  @Test
  fun a_tap_reports_at_once_when_no_gesture_would_use_a_second_one() =
    fixture.runRecognitionTest(
      options =
        InputConfiguration {
          bindings { doubleTap { enabled = false } }
          bindings { tapDrag { enabled = false } }
        }
    ) { target ->
      mainClock.autoAdvance = false
      try {
        mapNode().performTouchInput { click(center) }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(1, target.clicks, "the tap waited for a double tap no gesture would use")
      } finally {
        mainClock.autoAdvance = true
      }
    }

  @Test
  fun a_second_tap_inside_the_bounce_window_still_clicks_when_no_gesture_awaits_it() =
    fixture.runRecognitionTest(
      options =
        InputConfiguration {
          bindings { doubleTap { enabled = false } }
          bindings { tapDrag { enabled = false } }
        }
    ) { target ->
      mapNode().performTouchInput {
        down(center)
        up()
        advanceEventTime(10)
        down(center)
        up()
      }
      waitForIdle()
      assertEquals(2, target.clicks, "a bounce filter discarded a tap no gesture would pair")
    }

  @Test
  fun double_click_zooms_and_reports_its_first_click() = fixture.runRecognitionTest { target ->
    mapNode().performMouseInput { doubleClick() }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.isNotEmpty() }
    assertEquals(1, target.clicks, "a double click did not report exactly its first click")
    assertTrue(target.scaleCalls.any { it.scale > 1.0 }, "a double click did not zoom in")
  }

  @Test
  fun double_tap_zooms_without_reporting_the_first_tap() = fixture.runRecognitionTest { target ->
    mapNode().performTouchInput { doubleClick() }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.any { it.scale > 1.0 } }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertEquals(0, target.clicks, "a double tap leaked its first tap as a click")
  }

  @Test
  fun quick_zoom_does_not_leak_its_first_tap() = fixture.runRecognitionTest { target ->
    mapNode().performTouchInput {
      click(center)
      advanceEventTime(SECOND_TAP_GAP_MILLIS)
      down(0, center)
      moveTo(0, center + Offset(0f, 100f), delayMillis = 100)
      up(0)
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.any { it.scale > 1.0 } }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertEquals(0, target.clicks, "a quick zoom leaked its first tap as a click")
  }

  @Test
  fun mouse_quick_zoom_reports_its_first_click() = fixture.runRecognitionTest { target ->
    mapNode().performMouseInput {
      click(center)
      advanceEventTime(SECOND_TAP_GAP_MILLIS)
      press()
      moveBy(Offset(0f, 100f))
      release()
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.any { it.scale > 1.0 } }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertEquals(1, target.clicks, "a mouse quick zoom did not report exactly its first click")
    assertEquals(0, target.moveCalls.size, "a mouse quick zoom panned")
  }

  @Test
  fun horizontal_motion_disqualifies_quick_zoom() = fixture.runRecognitionTest { target ->
    mapNode().performTouchInput {
      click(center)
      advanceEventTime(SECOND_TAP_GAP_MILLIS)
      down(0, center)
      moveTo(0, center + Offset(100f, 0f), delayMillis = 50)
      up(0)
    }
    mainClock.advanceTimeBy(500)
    waitForIdle()
    assertEquals(0, target.scaleCalls.size, "a rejected quick zoom scaled")
    assertEquals(0, target.moveCalls.size, "the disqualifying move panned")
  }

  @Test
  fun horizontal_mouse_motion_disqualifies_quick_zoom() = fixture.runRecognitionTest { target ->
    mapNode().performMouseInput {
      click(center)
      advanceEventTime(SECOND_TAP_GAP_MILLIS)
      press()
      moveBy(Offset(100f, 0f))
      release()
    }
    mainClock.advanceTimeBy(500)
    waitForIdle()
    assertEquals(0, target.scaleCalls.size, "a rejected mouse quick zoom scaled")
    assertEquals(0, target.moveCalls.size, "the disqualifying move panned")
    assertEquals(1, target.clicks, "a rejected mouse quick zoom did not report its first click")
  }

  @Test
  fun a_quick_zoom_only_configuration_can_pair_its_initial_press() {
    fixture.runRecognitionTest(
      options =
        InputConfiguration(InputConfiguration.NoBindings) {
          camera { zoom { momentum { enabled = false } } }
          bindings { tapDrag { enabled = true } }
        }
    ) { target ->
      mapNode().performTouchInput {
        click(center)
        advanceEventTime(SECOND_TAP_GAP_MILLIS)
        down(center)
        moveBy(Offset(0f, 60f))
        up()
      }
      waitForIdle()
      assertTrue(target.scaleCalls.isNotEmpty())
      assertEquals(0, target.clicks)
    }
  }

  @Test
  fun no_click_subscribers_or_pairing_demand_leaves_an_ordinary_tap_to_the_parent() {
    var parentClicks = 0
    fixture.runRecognitionTest(
      options =
        InputConfiguration {
          bindings {
            doubleTap { mappings {} }
            tapDrag { enabled = false }
            twoFingerTap { mappings {} }
          }
        },
      parentOnClick = { parentClicks++ },
    ) { target ->
      target.clickFamilies = emptySet()
      mapNode().performTouchInput { click(center) }
      waitForIdle()
      assertEquals(1, parentClicks)
      assertEquals(0, target.clicks)
      assertEquals(0, target.startedCount)
    }
  }

  @Test
  fun two_finger_tap_only_claims_its_press_against_a_parent_click() {
    var parentClicks = 0
    fixture.runRecognitionTest(
      options =
        InputConfiguration(InputConfiguration.NoBindings) {
          bindings {
            twoFingerTap {
              enabled = true
              mappings { otherwise(TapResponse.ZoomOut) }
            }
          }
        },
      parentOnClick = { parentClicks++ },
    ) { target ->
      mapNode().performTouchInput {
        down(0, center - Offset(40f, 0f))
        down(1, center + Offset(40f, 0f))
        up(0)
        up(1)
      }
      waitForIdle()
      assertEquals(0, parentClicks)
      assertTrue(target.scaleCalls.single().scale < 1.0)
    }
  }

  @Test
  fun two_finger_tap_requests_a_zoom_out() = fixture.runRecognitionTest { target ->
    mapNode().performTouchInput {
      down(0, center - Offset(40f, 0f))
      down(1, center + Offset(40f, 0f))
      up(0)
      up(1)
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.any { it.scale < 1.0 } }
  }
}
