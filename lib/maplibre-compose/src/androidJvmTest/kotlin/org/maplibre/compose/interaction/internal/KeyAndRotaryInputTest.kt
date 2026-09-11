package org.maplibre.compose.interaction.internal

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.log2
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.interaction.DragResponse
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.KeyResponse
import org.maplibre.compose.interaction.ModifierMatch
import org.maplibre.compose.map.GestureTestFixture
import org.maplibre.compose.map.RecordingGestureTarget

@OptIn(ExperimentalAtomicApi::class, ExperimentalTestApi::class)
class KeyAndRotaryInputTest {
  private val fixture = GestureTestFixture()

  @AfterTest fun closeMap() = fixture.close()

  @Test
  fun key_takeover_cancels_a_held_drag_without_waiting_for_another_pointer_event() {
    fixture.runRecognitionTest { target ->
      val map = mapNode()
      map.performTouchInput {
        down(center)
        moveBy(Offset(30f, 0f))
      }
      waitForIdle()
      map.performKeyInput { pressKey(Key.DirectionRight) }
      waitForIdle()
      assertEquals(2, target.startedCount)
      val moves = target.moveCalls.size
      map.performTouchInput {
        moveBy(Offset(30f, 0f))
        up()
      }
      waitForIdle()
      assertEquals(moves, target.moveCalls.size, "a held contact took camera authority back")
    }
  }

  @Test
  fun none_leaves_clicks_and_focus_to_the_parent() {
    var parentClicks = 0
    fixture.runRecognitionTest(
      options = InputConfiguration.NoBindings,
      parentOnClick = { parentClicks++ },
    ) { target ->
      mapNode().performMouseInput { click(center) }
      waitForIdle()
      assertEquals(1, parentClicks)
      assertEquals(0, target.clicks)
      assertEquals(0, target.startedCount)
      mapNode().assert(expectValue(SemanticsProperties.StateDescription, "not engaged"))
    }
  }

  @Test
  fun a_drag_enabled_mid_press_engages_keyboard_controls() =
    fixture.runRecognitionTest(
      options =
        InputConfiguration(from = InputConfiguration.NoBindings) {
          bindings {
            drag {
              enabled = true
              mappings {
                on(
                  modifiers = ModifierMatch.Containing(KeyModifier.Ctrl),
                  response = DragResponse.Pan,
                )
              }
            }
            keys {
              enabled = true
              mappings { on(Key.DirectionRight, response = KeyResponse.PanRight) }
            }
          }
          camera { pan { momentum { enabled = false } } }
        }
    ) { target ->
      val map = mapNode()
      map.performMouseInput { press() }
      map.performKeyInput { keyDown(Key.CtrlLeft) }
      map.performMouseInput {
        moveBy(Offset(20f, 0f))
        moveBy(Offset(20f, 0f))
        release()
      }
      map.performKeyInput { keyUp(Key.CtrlLeft) }
      waitForIdle()
      assertTrue(target.moveCalls.isNotEmpty())
      map.assertIsFocused()
      val moves = target.moveCalls.size
      map.performKeyInput { pressKey(Key.DirectionRight) }
      waitForIdle()
      assertTrue(target.moveCalls.size > moves)
    }

  @Test
  fun shift_and_arrow_keys_request_rotate_and_tilt() = fixture.runRecognitionTest { target ->
    val map = mapNode()
    map.performMouseInput { click(Offset(10f, 10f)) }
    map.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) } }
    waitUntil(timeoutMillis = TIMEOUT) { target.rotateCalls.any { it.bearingDelta != 0.0 } }
    map.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionUp) } }
    waitUntil(timeoutMillis = TIMEOUT) { target.rotateCalls.any { it.pitchDelta != 0.0 } }
  }

  @Test
  fun tab_focuses_the_map_without_engaging_it() = fixture.runFocusTest { target, unconsumed ->
    onNodeWithTag(BEFORE_MAP_TAG).requestFocus()
    mapNode().performKeyInput { pressKey(Key.Tab) }
    mapNode().assertIsFocused()

    mapNode().performKeyInput { pressKey(Key.DirectionRight) }
    waitForIdle()

    assertEquals(0, target.moveCalls.size, "a direction key panned a map that no key engaged")
    assertTrue(Key.DirectionRight in unconsumed, "the map consumed the direction key")
  }

  @Test
  fun enter_engages_the_map_so_a_direction_key_pans() = fixture.runFocusTest { target, unconsumed ->
    onNodeWithTag(BEFORE_MAP_TAG).requestFocus()
    mapNode().performKeyInput { pressKey(Key.Tab) }

    mapNode().performKeyInput { pressKey(Key.Enter) }
    mapNode().performKeyInput { pressKey(Key.DirectionRight) }

    waitUntil(timeoutMillis = TIMEOUT) { target.moveCalls.isNotEmpty() }
    assertFalse(Key.Enter in unconsumed, "the map passed Enter through")
    assertFalse(
      Key.DirectionRight in unconsumed,
      "the engaged map passed the direction key through",
    )
    mapNode().assertIsFocused()
  }

  @Test
  fun escape_disengages_the_map_and_the_next_direction_key_passes_through() =
    fixture.runFocusTest { target, unconsumed ->
      onNodeWithTag(BEFORE_MAP_TAG).requestFocus()
      mapNode().performKeyInput { pressKey(Key.Tab) }
      mapNode().performKeyInput { pressKey(Key.Enter) }

      mapNode().performKeyInput { pressKey(Key.Escape) }
      mapNode().performKeyInput { pressKey(Key.DirectionRight) }
      waitForIdle()

      assertFalse(Key.Escape in unconsumed, "the engaged map passed Escape through")
      assertEquals(0, target.moveCalls.size, "a direction key panned after Escape")
      assertTrue(Key.DirectionRight in unconsumed, "the map consumed the direction key")
    }

  @Test
  fun back_disengages_a_map_that_a_key_engaged() = fixture.runFocusTest { target, unconsumed ->
    onNodeWithTag(BEFORE_MAP_TAG).requestFocus()
    mapNode().performKeyInput { pressKey(Key.Tab) }
    mapNode().performKeyInput { pressKey(Key.Enter) }

    mapNode().performKeyInput { pressKey(Key.Back) }
    mapNode().performKeyInput { pressKey(Key.DirectionRight) }
    waitForIdle()

    assertFalse(Key.Back in unconsumed, "the key-engaged map passed Back through")
    assertEquals(0, target.moveCalls.size, "a direction key panned after Back")
  }

  @Test
  fun a_click_engages_the_map_without_consuming_back() =
    fixture.runFocusTest { target, unconsumed ->
      val map = mapNode()
      map.performMouseInput { click(Offset(10f, 10f)) }
      map.performKeyInput { pressKey(Key.DirectionRight) }
      waitUntil(timeoutMillis = TIMEOUT) { target.moveCalls.isNotEmpty() }

      map.performKeyInput { pressKey(Key.Back) }
      waitForIdle()

      assertTrue(Key.Back in unconsumed, "the pointer-engaged map consumed Back")
    }

  @Test
  fun a_map_with_every_keyboard_gesture_disabled_takes_no_tab_stop() =
    fixture.runFocusTest(
      options =
        InputConfiguration {
          bindings {
            keys {
              mappings {}
            }
          }
        }
    ) { _, _ ->
      onNodeWithTag(BEFORE_MAP_TAG).requestFocus()
      mapNode().performKeyInput { pressKey(Key.Tab) }
      onNodeWithTag(AFTER_MAP_TAG).assertIsFocused()
    }

  @Test
  fun a_rotary_only_map_takes_a_tab_stop() =
    fixture.runFocusTest(
      options =
        InputConfiguration {
          bindings {
            keys {
              mappings {}
            }
          }
        },
      rotaryNotchPixels = 24f,
    ) { _, unconsumed ->
      onNodeWithTag(BEFORE_MAP_TAG).requestFocus()
      mapNode().performKeyInput { pressKey(Key.Tab) }
      mapNode().assertIsFocused()

      mapNode().performKeyInput { pressKey(Key.Enter) }
      mapNode().performKeyInput { pressKey(Key.Back) }
      mapNode().performMouseInput { click(Offset(10f, 10f)) }
      waitForIdle()

      assertTrue(Key.Enter in unconsumed, "a map with no key binding engaged on Enter")
      assertTrue(Key.Back in unconsumed, "a map with no key binding consumed Back")
      mapNode().assert(expectValue(SemanticsProperties.StateDescription, "not engaged"))
    }

  @Test
  fun disabling_all_bindings_while_a_key_is_held_still_consumes_its_release() {
    var options by mutableStateOf(InputConfiguration.Standard)
    fixture.runFocusTest(optionsProvider = { options }) { target, unconsumed ->
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput {
        pressKey(Key.Enter)
        keyDown(Key.DirectionRight)
      }
      waitUntil(timeoutMillis = TIMEOUT) { target.moveCalls.isNotEmpty() }
      runOnUiThread { options = InputConfiguration.NoBindings }
      waitForIdle()
      map.assertIsFocused()
      map.assert(expectValue(SemanticsProperties.StateDescription, "not engaged"))
      map.performKeyInput {
        advanceEventTime(600)
        keyUp(Key.DirectionRight)
      }
      waitForIdle()
      assertFalse(Key.DirectionRight in unconsumed, "the owed release escaped after disabling keys")
      onNodeWithTag(BEFORE_MAP_TAG).requestFocus()
      map.performKeyInput { pressKey(Key.Tab) }
      onNodeWithTag(AFTER_MAP_TAG).assertIsFocused()
    }
  }

  @Test
  fun losing_focus_clears_engagement_before_the_map_is_focused_again() =
    fixture.runFocusTest { target, unconsumed ->
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput { pressKey(Key.Enter) }
      onNodeWithTag(AFTER_MAP_TAG).requestFocus()
      map.requestFocus()
      map.performKeyInput { pressKey(Key.DirectionRight) }
      waitForIdle()
      assertTrue(target.moveCalls.isEmpty())
      assertTrue(Key.DirectionRight in unconsumed)
      map.assert(expectValue(SemanticsProperties.StateDescription, "not engaged"))
    }

  @Test
  fun an_invalid_rotary_notch_does_not_create_a_focus_stop() =
    fixture.runFocusTest(
      options =
        InputConfiguration(from = InputConfiguration.NoBindings) {
          bindings { rotary { enabled = true } }
        },
      rotaryNotchPixels = Float.POSITIVE_INFINITY,
    ) { _, _ ->
      onNodeWithTag(BEFORE_MAP_TAG).requestFocus()
      mapNode().performKeyInput { pressKey(Key.Tab) }
      onNodeWithTag(AFTER_MAP_TAG).assertIsFocused()
    }

  @Test
  fun camera_takeover_during_a_held_key_stops_motion_until_release() {
    fixture.runFocusTest { target, unconsumed ->
      mainClock.autoAdvance = false
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput {
        pressKey(Key.Enter)
        keyDown(Key.DirectionRight)
      }
      mainClock.advanceTimeBy(FRAME_MILLIS * 4)
      val moves = target.moveCalls.size
      assertTrue(moves > 0)
      lateinit var newer: CameraInputToken
      runOnUiThread { newer = target.onGestureStarted() }
      mainClock.advanceTimeBy(FRAME_MILLIS * 4)
      assertEquals(moves, target.moveCalls.size)
      map.performKeyInput { keyUp(Key.DirectionRight) }
      mainClock.advanceTimeBy(600)
      assertEquals(moves, target.moveCalls.size)
      assertFalse(Key.DirectionRight in unconsumed)
      runOnUiThread { target.onGestureEnded(newer) }
      map.performKeyInput { pressKey(Key.DirectionRight) }
      mainClock.advanceTimeBy(600)
      assertTrue(target.moveCalls.size > moves)
    }
  }

  @Test
  fun a_held_key_pans_every_frame_and_release_ends_the_session() {
    fixture.runFocusTest { target, _ ->
      mainClock.autoAdvance = false
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput {
        pressKey(Key.Enter)
        keyDown(Key.DirectionRight)
      }
      mainClock.advanceTimeByFrame()
      repeat(6) {
        mainClock.advanceTimeByFrame()
        assertEquals(it + 1, target.moveCalls.size, "frame ${it + 1}")
      }
      assertTrue(target.moveCalls.all { it.x < 0f && it.y == 0f }, "${target.moveCalls}")
      assertEquals(0, target.endedCount)
      map.performKeyInput { keyUp(Key.DirectionRight) }
      waitUntil(timeoutMillis = TIMEOUT) { target.endedCount == 1 }
      val settled = target.moveCalls.toList()
      mainClock.advanceTimeBy(600)
      assertEquals(settled, target.moveCalls)
    }
  }

  @Test
  fun a_tap_pans_one_step() {
    fixture.runFocusTest { target, _ ->
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput {
        pressKey(Key.Enter)
        pressKey(Key.DirectionRight)
      }
      waitForIdle()
      val panStep = InputConfiguration.Standard.bindings.keys.panStep.value
      assertEquals(-panStep, target.moveCalls.sumOf { it.x.toDouble() }.toFloat(), 1e-3f)
      assertEquals(1, target.endedCount)
    }
  }

  @Test
  fun focused_rotary_zooms_in_both_directions_without_engagement_and_ends_its_burst() {
    assumeRotaryInjectionSupported()
    fixture.runFocusTest(
      options =
        InputConfiguration(from = InputConfiguration.NoBindings) {
          bindings {
            rotary {
              enabled = true
            }
          }
        },
      rotaryNotchPixels = 24f,
    ) { target, _ ->
      mainClock.autoAdvance = false
      val map = mapNode()
      map.requestFocus()
      map.performRotaryScrollInput {
        rotateToScrollVertically(24f)
        advanceEventTime(16)
        rotateToScrollVertically(-24f)
      }
      waitForIdle()
      assertEquals(2, target.scaleCalls.size)
      assertTrue(target.scaleCalls[0].scale < 1.0)
      assertTrue(target.scaleCalls[1].scale > 1.0)
      assertTrue(target.scaleCalls.all { it.anchor == null })
      assertEquals(1, target.startedCount)
      assertEquals(0, target.endedCount)
      map.assert(expectValue(SemanticsProperties.StateDescription, "not engaged"))
      mainClock.advanceTimeBy(250)
      waitForIdle()
      assertEquals(1, target.endedCount)
      map.performRotaryScrollInput { rotateToScrollVertically(24f) }
      assertEquals(2, target.startedCount)
    }
  }

  @Test
  fun rotary_focus_loss_ends_the_burst_and_events_follow_the_new_focus() =
    fixture.runFocusTest(rotaryNotchPixels = 24f) { target, _ ->
      assumeRotaryInjectionSupported()
      mainClock.autoAdvance = false
      val map = mapNode()
      map.requestFocus()
      map.performRotaryScrollInput { rotateToScrollVertically(24f) }
      assertEquals(1, target.scaleCalls.size)
      onNodeWithTag(AFTER_MAP_TAG).requestFocus()
      waitForIdle()
      assertEquals(1, target.endedCount)
      map.performRotaryScrollInput { rotateToScrollVertically(24f) }
      assertEquals(1, target.scaleCalls.size)
      map.requestFocus()
      map.performRotaryScrollInput { rotateToScrollVertically(24f) }
      assertEquals(2, target.startedCount)
    }

  @Test
  fun a_key_takes_over_rotary_and_the_next_rotary_sample_starts_a_new_burst() =
    fixture.runFocusTest(rotaryNotchPixels = 24f) { target, _ ->
      assumeRotaryInjectionSupported()
      mainClock.autoAdvance = false
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput { pressKey(Key.Enter) }
      map.performRotaryScrollInput { rotateToScrollVertically(24f) }
      map.performKeyInput { pressKey(Key.DirectionRight) }
      waitForIdle()
      assertTrue(target.moveCalls.isNotEmpty())
      assertEquals(2, target.startedCount)
      assertEquals(2, target.endedCount)
      map.performRotaryScrollInput { rotateToScrollVertically(24f) }
      assertEquals(3, target.startedCount)
      assertEquals(2, target.scaleCalls.size)
    }

  private fun assumeRotaryInjectionSupported() {
    assumeTrue(
      "Compose Skiko rotary injection is a no-op",
      System.getProperty("java.vm.name") == "Dalvik",
    )
  }

  @Test
  fun plus_and_minus_request_zoom() = fixture.runRecognitionTest { target ->
    val map = mapNode()
    map.performMouseInput { click(Offset(10f, 10f)) }
    map.performKeyInput { pressKey(Key.Equals) }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.any { it.scale > 1.0 } }
    map.performKeyInput { pressKey(Key.Minus) }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.any { it.scale < 1.0 } }
  }

  @Test
  fun shifted_plus_zooms_but_an_extra_modifier_does_not_match() =
    fixture.runFocusTest { target, unconsumed ->
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput {
        pressKey(Key.Enter)
        withKeyDown(Key.ShiftLeft) {
          pressKey(Key.Equals)
          pressKey(Key.Plus)
        }
      }
      val zoomStep = InputConfiguration.Standard.bindings.keys.zoomStep
      waitUntil(timeoutMillis = TIMEOUT) { target.zoomed() >= 2 * zoomStep - 1e-6 }
      assertFalse(Key.Equals in unconsumed)
      assertFalse(Key.Plus in unconsumed)
      map.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Equals) } }
      waitForIdle()
      assertEquals(2 * zoomStep, target.zoomed(), 1e-6)
      assertTrue(Key.Equals in unconsumed)
    }

  @Test
  fun replacing_a_held_chord_does_not_reinterpret_its_release() {
    var options by mutableStateOf(InputConfiguration.Standard)
    fixture.runFocusTest(optionsProvider = { options }) { target, unconsumed ->
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput {
        pressKey(Key.Enter)
        keyDown(Key.DirectionRight)
      }
      waitUntil(timeoutMillis = TIMEOUT) { target.moveCalls.isNotEmpty() }
      runOnUiThread {
        options = InputConfiguration {
          bindings { keys { mappings { on(Key.DirectionRight, response = KeyResponse.ZoomIn) } } }
        }
      }
      waitForIdle()
      map.performKeyInput {
        advanceEventTime(600)
        keyUp(Key.DirectionRight)
      }
      waitForIdle()
      assertFalse(Key.DirectionRight in unconsumed)
      assertTrue(target.scaleCalls.isEmpty())
      map.performKeyInput { pressKey(Key.DirectionRight) }
      waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.isNotEmpty() }
    }
  }

  private fun RecordingGestureTarget.zoomed(): Double = scaleCalls.sumOf { log2(it.scale) }
}
