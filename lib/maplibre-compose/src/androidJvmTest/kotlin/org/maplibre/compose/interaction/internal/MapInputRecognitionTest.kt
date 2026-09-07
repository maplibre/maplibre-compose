package org.maplibre.compose.interaction.internal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTrackpadInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.interaction.CameraInputStart
import org.maplibre.compose.interaction.ClickEvent
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.ModifierMatch
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.compose.map.GestureTestFixture
import org.maplibre.compose.map.RecordingGestureTarget
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.mlnffi.runPlainComposeUiTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.scaledBy
import org.maplibre.compose.style.systemAnimatorDurationScale
import org.maplibre.spatialk.geojson.Position

private const val RECOGNITION_MAP_TAG = "recognition-map"
private const val BEFORE_MAP_TAG = "before-map"
private const val AFTER_MAP_TAG = "after-map"

/** Skips tests when the Compose test host cannot inject pan and scale events. */
internal expect fun assumeTrackpadEventInjectionSupported()

/**
 * Gesture recognition and binding for [mapInput], hosted on a recording [CameraInputTarget].
 *
 * These cases do not create a MapLibre map. Native camera effects of moveBy, scaleBy, and
 * rotateAndPitchBy live in CameraMoveReportingTest.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalTestApi::class)
class MapInputRecognitionTest {
  private val fixture = GestureTestFixture()

  @AfterTest fun closeMap() = fixture.close()

  @Test
  fun contacts_rejected_before_a_viewport_wait_for_release() = runRecognitionTest { target ->
    val viewport = target.currentViewport
    target.currentViewport = null
    val map = mapNode()
    map.performMouseInput {
      moveTo(center)
      press()
    }
    target.currentViewport = viewport
    map.performMouseInput {
      moveBy(Offset(20f, 0f))
      release()
    }
    waitForIdle()
    assertTrue(target.moveCalls.isEmpty())
    assertEquals(0, target.clicks)
    map.performMouseInput {
      press()
      moveBy(Offset(20f, 0f))
      release()
    }
    waitForIdle()
    assertTrue(target.moveCalls.isNotEmpty())
  }

  @Test
  fun a_closed_map_ignores_input_while_still_composed() = runRecognitionTest { target ->
    val map = mapNode()
    map.requestFocus()
    fixture.state.close()
    map.performKeyInput {
      pressKey(Key.Enter)
      pressKey(Key.DirectionRight)
    }
    map.performRotaryScrollInput { rotateToScrollVertically(100f) }
    map.performMouseInput {
      moveTo(center)
      press()
      moveBy(Offset(20f, 0f))
      release()
      scroll(1f)
    }
    waitForIdle()
    assertTrue(target.moveCalls.isEmpty())
    assertTrue(target.scaleCalls.isEmpty())
    assertEquals(0, target.startedCount)
    assertEquals(0, target.clicks)
  }

  @Test
  fun trackpad_pan_and_scale_work_independently_of_scroll_bindings() {
    assumeTrackpadEventInjectionSupported()
    runRecognitionTest(options = MapInteractions { bindings { scroll { enabled = false } } }) {
      target ->
      mapNode().performTrackpadInput {
        moveTo(Offset(80f, 80f))
        panStart()
        scaleStart()
        panMoveBy(Offset(20f, 10f))
        scaleChangeBy(1.5f)
        panEnd()
        scaleEnd()
      }
      waitForIdle()
      assertEquals(1, target.moveCalls.size)
      assertEquals(1.5, target.scaleCalls.single().scale, 1e-6)
      assertEquals(0, target.clicks)
    }
  }

  @Test
  fun a_structural_restart_suppresses_trackpad_changes_until_the_old_component_ends() {
    assumeTrackpadEventInjectionSupported()
    var options by mutableStateOf(MapInteractions.Standard)
    runRecognitionTest(optionsProvider = { options }) { target ->
      val map = mapNode()
      map.performTrackpadInput {
        moveTo(Offset(80f, 80f))
        scaleStart()
        scaleChangeBy(1.5f)
      }
      runOnIdle {
        options = MapInteractions { bindings { transform { zoom { zoomScale = 2.0 } } } }
      }
      waitForIdle()
      map.performTrackpadInput {
        scaleChangeBy(1.5f)
        scaleEnd()
      }
      waitForIdle()
      assertEquals(1, target.endedCount)
      assertEquals(1, target.scaleCalls.size)
      map.performTrackpadInput {
        scaleStart()
        scaleChangeBy(2f)
        scaleEnd()
      }
      waitForIdle()
      assertEquals(2, target.scaleCalls.size)
      assertEquals(4.0, target.scaleCalls.last().scale, 1e-6)
    }
  }

  @Test
  fun shift_drag_draws_a_selection_then_fits_under_the_same_session() =
    runRecognitionTest { target ->
      target.project = { Position(it.x.value.toDouble(), -it.y.value.toDouble()) }
      val map = mapNode()
      val before = map.captureToImage().toPixelMap()[50, 50]
      map.performKeyInput { keyDown(Key.ShiftLeft) }
      map.performMouseInput {
        moveTo(Offset(20f, 20f))
        press()
        moveTo(Offset(120f, 80f))
      }
      waitForIdle()
      assertTrue(before != map.captureToImage().toPixelMap()[50, 50])
      assertTrue(target.fitCalls.isEmpty())
      map.performMouseInput { release() }
      map.performKeyInput { keyUp(Key.ShiftLeft) }
      waitForIdle()
      assertEquals(before, map.captureToImage().toPixelMap()[50, 50])
      assertEquals(1, target.fitCalls.size)
      assertEquals(
        MapInteractions.Standard.animationDuration.scaledBy(systemAnimatorDurationScale()),
        target.fitCalls.single().second,
      )
      assertEquals(1, target.startedCount)
      assertEquals(1, target.endedCount)
      assertTrue(target.moveCalls.isEmpty())
      assertEquals(0, target.clicks)
    }

  @Test
  fun cancelling_box_zoom_clears_the_preview_without_fitting() {
    var configuration by mutableStateOf(MapInteractions.Standard)
    runRecognitionTest(optionsProvider = { configuration }) { target ->
      target.project = { Position(it.x.value.toDouble(), -it.y.value.toDouble()) }
      val map = mapNode()
      val before = map.captureToImage().toPixelMap()[50, 50]
      map.performKeyInput { keyDown(Key.ShiftLeft) }
      map.performMouseInput {
        moveTo(Offset(20f, 20f))
        press()
        moveTo(Offset(120f, 80f))
      }
      waitForIdle()
      assertTrue(before != map.captureToImage().toPixelMap()[50, 50])
      runOnIdle { configuration = MapInteractions.None }
      waitForIdle()
      assertEquals(before, map.captureToImage().toPixelMap()[50, 50])
      map.performMouseInput { release() }
      map.performKeyInput { keyUp(Key.ShiftLeft) }
      waitForIdle()
      assertTrue(target.fitCalls.isEmpty())
      assertEquals(1, target.endedCount)
    }
  }

  @Test
  fun a_thin_box_ends_without_a_fit_or_click() = runRecognitionTest { target ->
    target.project = { Position(it.x.value.toDouble(), -it.y.value.toDouble()) }
    val map = mapNode()
    map.performKeyInput { keyDown(Key.ShiftLeft) }
    map.performMouseInput {
      moveTo(Offset(20f, 20f))
      press()
      moveTo(Offset(120f, 21f))
      release()
    }
    map.performKeyInput { keyUp(Key.ShiftLeft) }
    waitForIdle()
    assertTrue(target.fitCalls.isEmpty())
    assertEquals(1, target.endedCount)
    assertEquals(0, target.clicks)
  }

  @Test
  fun two_finger_tap_only_claims_its_press_against_a_parent_click() {
    var parentClicks = 0
    runRecognitionTest(
      options =
        MapInteractions(MapInteractions.None) {
          bindings {
            twoFingerTap {
              enabled = true
              mappings { otherwise { zoomOut() } }
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
  fun consuming_a_double_tap_binding_suppresses_zoom_and_map_delivery() {
    val doubles = mutableListOf<ClickEvent>()
    runRecognitionTest(
      options =
        MapInteractions {
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
    runRecognitionTest(
      options =
        MapInteractions {
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
    runRecognitionTest(
      options =
        MapInteractions {
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
    runRecognitionTest(
      options =
        MapInteractions {
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
  fun a_quick_zoom_only_configuration_can_pair_its_initial_press() {
    runRecognitionTest(
      options =
        MapInteractions(MapInteractions.None) {
          bindings {
            tapDrag {
              enabled = true
              momentum { enabled = false }
            }
          }
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
  fun an_unmatched_contact_does_not_cancel_an_active_scroll_burst() {
    runRecognitionTest(
      options =
        MapInteractions(MapInteractions.None) {
          bindings {
            scroll {
              enabled = true
              mappings { otherwise { zoom() } }
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
  fun camera_start_takeover_survives_final_pass_consumption() {
    var recorded: RecordingGestureTarget? = null
    var newer: CameraInputToken? = null
    runRecognitionTest(
      options =
        MapInteractions {
          camera {
            pan {
              onStart { newer = checkNotNull(recorded).onGestureStarted() }
            }
          }
        },
      parentModifier = Modifier.consumePointerEvents(PointerEventPass.Main, PointerEventType.Move),
    ) { target ->
      recorded = target
      mapNode().performTouchInput {
        down(center)
        moveBy(Offset(30f, 0f))
        up()
      }
      waitForIdle()
      assertTrue(target.moveCalls.isEmpty())
      assertTrue(checkNotNull(newer).acceptsCommands)
      target.onGestureEnded(checkNotNull(newer))
    }
  }

  @Test
  fun key_takeover_cancels_a_held_drag_without_waiting_for_another_pointer_event() {
    runRecognitionTest { target ->
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
  fun standard_scroll_zooms_for_fractional_whole_and_two_axis_deltas() =
    runRecognitionTest { target ->
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
    runRecognitionTest(optionsProvider = { options }) { target ->
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
  fun none_leaves_clicks_and_focus_to_the_parent() {
    var parentClicks = 0
    runRecognitionTest(options = MapInteractions.None, parentOnClick = { parentClicks++ }) { target
      ->
      mapNode().performMouseInput { click(center) }
      waitForIdle()
      assertEquals(1, parentClicks)
      assertEquals(0, target.clicks)
      assertEquals(0, target.startedCount)
      mapNode().assert(expectValue(SemanticsProperties.StateDescription, "not engaged"))
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
          MapInteractions(from = MapInteractions.None) {
            bindings {
              longPress {
                enabled = true
                mappings { otherwise { zoomIn() } }
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
        MapInteractions(from = MapInteractions.None) {
          bindings {
            drag {
              enabled = true
              pointerTypes = setOf(PointerType.Touch)
              mappings { otherwise { pan() } }
            }
          }
        }
      )
    runRecognitionTest(optionsProvider = { configuration }) { target ->
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
          MapInteractions(from = configuration) { bindings { drag { enabled = false } } }
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
  fun a_structural_change_cancels_the_drag_and_waits_for_existing_contacts_to_lift() {
    var configuration by mutableStateOf(MapInteractions.Standard)
    runRecognitionTest(optionsProvider = { configuration }) { target ->
      val map = mapNode()
      map.performTouchInput {
        down(center)
        moveBy(Offset(30f, 0f))
      }
      waitForIdle()
      val moves = target.moveCalls.size
      runOnIdle {
        configuration =
          MapInteractions(from = configuration) {
            bindings {
              drag { pan { startSlop = 8.dp } }
            }
          }
      }
      map.performTouchInput {
        moveBy(Offset(30f, 0f))
        up()
      }
      waitForIdle()
      assertEquals(1, target.endedCount)
      assertEquals(moves, target.moveCalls.size)
      map.performTouchInput {
        down(center)
        moveBy(Offset(30f, 0f))
        up()
      }
      waitForIdle()
      assertEquals(2, target.startedCount)
    }
  }

  @Test
  fun semantic_pan_start_runs_before_its_first_camera_command() {
    var recorded: RecordingGestureTarget? = null
    val counts = mutableListOf<Int>()
    runRecognitionTest(
      options =
        MapInteractions {
          camera {
            pan {
              onStart { counts += checkNotNull(recorded).moveCalls.size }
            }
          }
        }
    ) { target ->
      recorded = target
      mapNode().performTouchInput {
        down(center)
        moveBy(Offset(30f, 0f))
        up()
      }
      waitForIdle()
      assertEquals(listOf(0), counts)
      assertTrue(target.moveCalls.isNotEmpty())
    }
  }

  @Test
  fun split_axis_scroll_keeps_panning_when_horizontal_events_add_shift() {
    runRecognitionTest(
      options =
        MapInteractions {
          bindings {
            scroll {
              mappings { otherwise { pan() } }
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
    runRecognitionTest(
      options =
        MapInteractions {
          bindings {
            scroll {
              mappings {
                on(modifiers = ModifierMatch.Containing(KeyModifier.Ctrl)) { zoom() }
                otherwise { pan() }
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
    runRecognitionTest(
      options = MapInteractions { bindings { scroll { mappings { otherwise { zoom() } } } } },
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
  fun a_scroll_burst_keeps_one_token_and_appends_no_momentum() = runRecognitionTest { target ->
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
    runRecognitionTest(
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
  fun an_initial_consumed_press_cannot_click_drag_or_engage_the_map() =
    runRecognitionTest(
      parentModifier =
        Modifier.consumePointerEvents(PointerEventPass.Initial, PointerEventType.Press)
    ) { target ->
      mapNode().performTouchInput {
        down(center)
        moveBy(Offset(60f, 0f))
        advanceEventTime(1_000)
        up()
      }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      assertEquals(0, target.startedCount)
      assertEquals(0, target.clicks)
      assertEquals(0, target.longClicks)
      mapNode().assert(expectValue(SemanticsProperties.StateDescription, "not engaged"))
    }

  @Test
  fun a_parent_consuming_unclaimed_motion_in_main_cancels_click_and_long_press() =
    runRecognitionTest(
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
  fun a_consumed_drag_move_cancels_without_fling_and_waits_for_all_contacts_to_lift() {
    var intercept = false
    runRecognitionTest(
      parentModifier =
        Modifier.pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent(PointerEventPass.Initial)
              if (intercept) event.changes.forEach { it.consume() }
            }
          }
        }
    ) { target ->
      val map = mapNode()
      map.performTouchInput {
        down(center)
        moveBy(Offset(30f, 0f))
      }
      waitForIdle()
      val movements = target.moveCalls.size
      assertTrue(movements > 0)
      runOnIdle { intercept = true }
      map.performTouchInput { moveBy(Offset(30f, 0f)) }
      runOnIdle { intercept = false }
      map.performTouchInput {
        moveBy(Offset(30f, 0f))
        up()
      }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      assertEquals(movements, target.moveCalls.size)
      assertEquals(1, target.startedCount)
      assertEquals(1, target.endedCount)
      map.performTouchInput {
        down(center)
        moveBy(Offset(30f, 0f))
        up()
      }
      waitForIdle()
      assertEquals(2, target.startedCount, "a later independent press remained suppressed")
    }
  }

  @Test
  fun a_consumed_release_does_not_click_or_launch_a_fling() =
    runRecognitionTest(
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
  fun the_arenas_own_consumption_does_not_cancel_its_drag_in_final() =
    runRecognitionTest { target ->
      mapNode().performTouchInput {
        down(center)
        moveBy(Offset(30f, 0f))
        moveBy(Offset(30f, 0f))
        moveBy(Offset(30f, 0f))
        up()
      }
      waitForIdle()
      assertTrue(target.moveCalls.size >= 3)
      assertEquals(1, target.startedCount)
      assertEquals(1, target.endedCount)
    }

  @Test
  fun a_press_that_jitters_within_the_slop_still_clicks() = runRecognitionTest { target ->
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
  fun a_press_past_the_slop_drags_instead_of_clicking() = runRecognitionTest { target ->
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
  fun a_map_click_does_not_also_click_its_parent() {
    val parentClicks = AtomicInt(0)
    runRecognitionTest(parentOnClick = { parentClicks.incrementAndFetch() }) { target ->
      mapNode().performMouseInput { click(center) }
      awaitClicks(target, 1)
      waitForIdle()
      assertEquals(0, parentClicks.load())
    }
  }

  @Test
  fun a_map_long_click_does_not_also_long_click_its_parent() {
    val parentLongClicks = AtomicInt(0)
    runRecognitionTest(parentOnLongClick = { parentLongClicks.incrementAndFetch() }) { target ->
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
  fun a_tap_waits_for_a_second_one_that_could_still_arrive() = runRecognitionTest { target ->
    mainClock.autoAdvance = false
    try {
      mapNode().performTouchInput { click(center) }
      mainClock.advanceTimeByFrame()
      waitForIdle()
      assertEquals(0, target.clicks, "the tap reported before a double tap could rule it out")
    } finally {
      mainClock.autoAdvance = true
    }
    awaitClicks(target, 1)
  }

  @Test
  fun a_tap_reports_at_once_when_no_gesture_would_use_a_second_one() =
    runRecognitionTest(
      options =
        MapInteractions {
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
  fun a_bounce_faster_than_the_min_time_is_not_a_double_tap() = runRecognitionTest { target ->
    mapNode().performTouchInput {
      down(center)
      up()
      advanceEventTime(10)
      down(center)
      up()
    }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertEquals(0, target.scaleCalls.size, "a bounce zoomed")
  }

  @Test
  fun a_second_tap_inside_the_bounce_window_still_clicks_when_no_gesture_awaits_it() =
    runRecognitionTest(
      options =
        MapInteractions {
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
  fun double_click_zooms_and_reports_its_first_click() = runRecognitionTest { target ->
    mapNode().performMouseInput { doubleClick() }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.isNotEmpty() }
    assertEquals(1, target.clicks, "a double click did not report exactly its first click")
    assertTrue(target.scaleCalls.any { it.scale > 1.0 }, "a double click did not zoom in")
  }

  @Test
  fun double_tap_zooms_without_reporting_the_first_tap() = runRecognitionTest { target ->
    mapNode().performTouchInput { doubleClick() }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.any { it.scale > 1.0 } }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertEquals(0, target.clicks, "a double tap leaked its first tap as a click")
  }

  @Test
  fun a_drag_enabled_mid_press_engages_keyboard_controls() =
    runRecognitionTest(
      options =
        MapInteractions(from = MapInteractions.None) {
          bindings {
            drag {
              enabled = true
              mappings { on(modifiers = ModifierMatch.Containing(KeyModifier.Ctrl)) { pan() } }
            }
            keys {
              enabled = true
              mappings { on(Key.DirectionRight) { panRight() } }
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
  fun a_second_touch_interrupts_camera_motion_before_the_pair_crosses_slop() =
    runRecognitionTest(
      options =
        MapInteractions(MapInteractions.None) {
          bindings { transform { pan { enabled = true } } }
        }
    ) { target ->
      val map = mapNode()
      map.performTouchInput { down(0, center - Offset(30f, 0f)) }
      val animation =
        CoroutineScope(Dispatchers.Unconfined).launch {
          fixture.state.animateCameraPosition(CameraPosition(zoom = 8.0))
        }
      try {
        assertFalse(animation.isCompleted)
        map.performTouchInput { down(1, center + Offset(30f, 0f)) }
        assertTrue(animation.isCancelled)
        assertTrue(target.moveCalls.isEmpty())
        map.performTouchInput {
          up(0)
          up(1)
        }
      } finally {
        animation.cancel()
      }
    }

  @Test
  fun plus_and_minus_request_zoom() = runRecognitionTest { target ->
    val map = mapNode()
    map.performMouseInput { click(Offset(10f, 10f)) }
    map.performKeyInput { pressKey(Key.Equals) }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.any { it.scale > 1.0 } }
    map.performKeyInput { pressKey(Key.Minus) }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.any { it.scale < 1.0 } }
  }

  @Test
  fun shift_and_arrow_keys_request_rotate_and_tilt() = runRecognitionTest { target ->
    val map = mapNode()
    map.performMouseInput { click(Offset(10f, 10f)) }
    map.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) } }
    waitUntil(timeoutMillis = TIMEOUT) { target.rotateCalls.any { it.bearingDelta != 0.0 } }
    map.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionUp) } }
    waitUntil(timeoutMillis = TIMEOUT) { target.rotateCalls.any { it.pitchDelta != 0.0 } }
  }

  @Test
  fun a_hover_does_not_end_a_scroll_hold() = runRecognitionTest { target ->
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
    runRecognitionTest(
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

  @Test
  fun secondary_mouse_drag_requests_rotate_and_tilt() = runRecognitionTest { target ->
    mapNode().performMouseInput {
      moveTo(center)
      press(MouseButton.Secondary)
      moveBy(Offset(80f, -40f), delayMillis = 50)
      release(MouseButton.Secondary)
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.rotateCalls.isNotEmpty() }
    assertTrue(target.rotateCalls.any { it.bearingDelta != 0.0 }, "a secondary drag did not rotate")
    assertTrue(target.rotateCalls.any { it.pitchDelta != 0.0 }, "a secondary drag did not tilt")
    assertEquals(0, target.moveCalls.size, "a secondary drag panned")
    assertTrue(
      target.rotateCalls.all { it.anchor == null },
      "default drag rotation must orbit the camera target",
    )
  }

  @Test
  fun pair_pan_keeps_its_camera_session_until_the_last_contact_lifts() {
    runRecognitionTest(
      options =
        MapInteractions(MapInteractions.None) {
          camera { pan { momentum { enabled = false } } }
          bindings {
            transform {
              pan {
                enabled = true

                startSlop = 20.dp
              }
            }
          }
        }
    ) { target ->
      val map = mapNode()
      map.performTouchInput {
        down(0, center - Offset(80f, 0f))
        down(1, center + Offset(80f, 0f))
        updatePointerBy(0, Offset(80f, 0f))
        updatePointerBy(1, Offset(80f, 0f))
        move()
        up(0)
      }
      waitForIdle()
      assertTrue(target.moveCalls.isNotEmpty())
      assertEquals(0, target.endedCount, "the remaining contact lost the shared camera session")
      map.performTouchInput { up(1) }
      waitForIdle()
      assertEquals(1, target.endedCount)
    }
  }

  @Test
  fun a_shove_cancels_the_started_pair_pan_before_tilt() {
    val order = mutableListOf<String>()
    runRecognitionTest(
      options =
        MapInteractions {
          camera {
            pan {
              momentum { enabled = false }
              onStart { order += "pan" }
            }
            tilt {
              momentum { enabled = false }
              onStart { order += "tilt" }
            }
          }
        }
    ) { target ->
      val map = mapNode()
      map.performTouchInput {
        down(0, center - Offset(80f, 0f))
        down(1, center + Offset(80f, 0f))
        repeat(16) {
          updatePointerBy(0, Offset(0f, -5f))
          updatePointerBy(1, Offset(0f, -5f))
          move(delayMillis = 20)
        }
      }
      waitForIdle()
      val panMoves = target.moveCalls.size
      assertTrue(panMoves > 0)
      map.performTouchInput {
        updatePointerBy(0, Offset(0f, -20f))
        updatePointerBy(1, Offset(0f, -20f))
        move(delayMillis = 20)
        up(0)
        up(1)
      }
      waitForIdle()
      assertEquals(panMoves, target.moveCalls.size, "tilt also panned the map")
      assertEquals(listOf("pan", "tilt"), order)
      assertEquals(1, target.startedCount)
      assertTrue(target.rotateCalls.any { it.pitchDelta != 0.0 })
      assertTrue(target.rotateCalls.all { it.bearingDelta == 0.0 }, "a shove also rotated")
    }
  }

  @Test
  fun rotation_cancels_pinch_and_additional_span_starts_a_new_pinch() {
    val starts = mutableListOf<String>()
    runRecognitionTest(
      options =
        MapInteractions {
          camera {
            zoom { onStart { starts += "zoom" } }
            rotate { onStart { starts += "rotate" } }
          }
          bindings {
            transform {
              pan {
                enabled = false
              }
            }
          }
          bindings { transform { tilt { enabled = false } } }
          bindings {
            transform {
              zoom {
                momentum { enabled = false }
              }
            }
          }
          bindings {
            transform {
              rotate {
                momentum { enabled = false }
              }
            }
          }
        }
    ) { target ->
      mapNode().performTouchInput {
        down(0, center - Offset(80f, 0f))
        down(1, center + Offset(80f, 0f))
        updatePointerTo(0, center - Offset(100f, 0f))
        updatePointerTo(1, center + Offset(100f, 0f))
        move(delayMillis = 20)
        updatePointerTo(0, center - Offset(0f, 100f))
        updatePointerTo(1, center + Offset(0f, 100f))
        move(delayMillis = 20)
        updatePointerTo(0, center - Offset(0f, 180f))
        updatePointerTo(1, center + Offset(0f, 180f))
        move(delayMillis = 20)
        up(0)
        up(1)
      }
      waitForIdle()
      assertEquals(listOf("zoom", "rotate", "zoom"), starts)
      assertEquals(2, target.scaleCalls.size)
      assertEquals(1, target.rotateCalls.size)
      assertEquals(1, target.startedCount)
    }
  }

  @Test
  fun replacing_a_selected_contact_ends_the_old_pair_and_rebases_the_new_pair() {
    runRecognitionTest(
      options =
        MapInteractions(MapInteractions.None) {
          camera { pan { momentum { enabled = false } } }
          bindings {
            transform {
              pan {
                enabled = true
              }
            }
          }
        }
    ) { target ->
      val map = mapNode()
      map.performTouchInput {
        down(10, center - Offset(80f, 0f))
        down(0, center + Offset(80f, 0f))
        updatePointerBy(10, Offset(40f, 0f))
        updatePointerBy(0, Offset(40f, 0f))
        move()
        down(2, center + Offset(0f, 80f))
      }
      waitForIdle()
      val moves = target.moveCalls.size
      map.performTouchInput {
        updatePointerBy(2, Offset(40f, 0f))
        move()
        up(10)
      }
      waitForIdle()
      assertEquals(
        moves,
        target.moveCalls.size,
        "an unselected contact or replacement jumped the camera",
      )
      map.performTouchInput {
        updatePointerBy(0, Offset(40f, 0f))
        updatePointerBy(2, Offset(40f, 0f))
        move()
        up(0)
        up(2)
      }
      waitForIdle()
      assertTrue(target.moveCalls.size > moves, "replacement pair never resumed panning")
      assertEquals(1, target.startedCount)
      assertEquals(1, target.endedCount)
    }
  }

  @Test
  fun lifting_a_pinch_does_not_turn_sub_slop_motion_into_a_pan() = runPlainComposeUiTest {
    val target = fixture.target
    var touchSlop = 0f
    setContent {
      touchSlop = LocalViewConfiguration.current.touchSlop
      GestureHost(target, MapInteractions.Standard)
    }
    waitForIdle()
    val map = mapNode()
    map.performTouchInput {
      down(0, center - Offset(80f, 0f))
      down(1, center + Offset(80f, 0f))
      repeat(6) {
        updatePointerBy(0, Offset(-24f, 0f))
        updatePointerBy(1, Offset(24f, 0f))
        move(delayMillis = 16)
      }
      up(0)
    }
    waitForIdle()
    val scales = target.scaleCalls.size
    assertTrue(scales > 0)
    map.performTouchInput {
      updatePointerBy(1, Offset(touchSlop / 2, 0f))
      move(delayMillis = 16)
      up(1)
    }
    waitForIdle()
    assertTrue(target.moveCalls.isEmpty(), "lifting the pinch started a pan")
    assertTrue(target.scaleCalls.size > scales, "pinch momentum was lost during release")
  }

  @Test
  fun a_new_pan_preserves_the_previous_pinchs_zoom_momentum() {
    runRecognitionTest { target ->
      val map = mapNode()
      map.performTouchInput {
        down(0, center - Offset(80f, 0f))
        down(1, center + Offset(80f, 0f))
        repeat(6) {
          updatePointerBy(0, Offset(-24f, 0f))
          updatePointerBy(1, Offset(24f, 0f))
          move(delayMillis = 16)
        }
        up(0)
        repeat(5) {
          updatePointerBy(1, Offset(80f, 0f))
          move(delayMillis = 16)
        }
      }
      waitForIdle()
      assertTrue(target.moveCalls.isNotEmpty(), "remaining contact did not start a pan")
      val moves = target.moveCalls.size
      val scales = target.scaleCalls.size
      assertTrue(scales > 0)
      map.performTouchInput { up(1) }
      waitForIdle()
      assertTrue(target.scaleCalls.size > scales, "new pan discarded zoom momentum")
      assertTrue(target.moveCalls.size > moves, "continued pan lost its own momentum")
      assertEquals(1, target.endedCount)
    }
  }

  @Test
  fun a_finger_departing_a_pinch_does_not_become_a_pan_fling() {
    runRecognitionTest { target ->
      val map = mapNode()
      map.performTouchInput {
        down(0, center - Offset(80f, 0f))
        down(1, center + Offset(80f, 0f))
        repeat(6) {
          updatePointerBy(0, Offset(-24f, 0f))
          updatePointerBy(1, Offset(24f, 0f))
          move(delayMillis = 16)
        }
        up(0)
        // A fast departing finger crosses slop during the interval between lifts.
        updatePointerBy(1, Offset(160f, 0f))
        move(delayMillis = 8)
      }
      waitForIdle()
      val moves = target.moveCalls.size
      val scales = target.scaleCalls.size
      assertTrue(moves > 0, "the remaining contact should still allow direct dragging")
      map.performTouchInput {
        advanceEventTime(7)
        up(1)
      }
      waitForIdle()
      assertEquals(moves, target.moveCalls.size, "departing finger became a pan fling")
      assertTrue(target.scaleCalls.size > scales, "pinch momentum was discarded")
    }
  }

  @Test
  fun newly_recognized_single_pan_discards_the_previous_pairs_staged_momentum() {
    runRecognitionTest(
      options =
        MapInteractions(MapInteractions.None) {
          camera { pan { momentum { minimumSpeed = 1.0 } } }
          bindings {
            drag {
              enabled = true

              mappings { on(button = PointerButton.Primary) { pan() } }
            }
            transform {
              pan {
                enabled = true
              }
            }
          }
        }
    ) { target ->
      val map = mapNode()
      map.performTouchInput {
        down(0, center - Offset(80f, 0f))
        down(1, center + Offset(80f, 0f))
        repeat(4) {
          updatePointerBy(0, Offset(20f, 0f))
          updatePointerBy(1, Offset(20f, 0f))
          move(delayMillis = 16)
        }
        up(0)
        // This starts a new pan after the old velocity samples have expired.
        updatePointerBy(1, Offset(-40f, 0f))
        move(delayMillis = 500)
      }
      waitForIdle()
      val moves = target.moveCalls.size
      assertTrue(target.moveCalls.last().x < 0)
      map.performTouchInput { up(1) }
      waitForIdle()
      assertEquals(moves, target.moveCalls.size, "old pair momentum survived new recognition")
      assertEquals(1, target.endedCount)
    }
  }

  @Test
  fun pair_pan_stages_momentum_until_the_group_lifts() {
    runRecognitionTest(
      options =
        MapInteractions(MapInteractions.None) {
          camera { pan { momentum { minimumSpeed = 1.0 } } }
          bindings {
            transform {
              pan {
                enabled = true
              }
            }
          }
        }
    ) { target ->
      val map = mapNode()
      map.performTouchInput {
        down(0, center - Offset(80f, 0f))
        down(1, center + Offset(80f, 0f))
        repeat(4) {
          updatePointerBy(0, Offset(20f, 0f))
          updatePointerBy(1, Offset(20f, 0f))
          move(delayMillis = 16)
        }
        up(0)
      }
      waitForIdle()
      val moves = target.moveCalls.size
      mainClock.advanceTimeBy(200)
      waitForIdle()
      assertEquals(moves, target.moveCalls.size, "momentum started with a contact still held")
      map.performTouchInput { up(1) }
      waitForIdle()
      assertTrue(target.moveCalls.size > moves, "the final lift discarded the staged pan")
      assertEquals(1, target.endedCount)
    }
  }

  @Test
  fun semantic_zoom_start_takeover_stops_pair_response() {
    var recorded: RecordingGestureTarget? = null
    var newer: CameraInputToken? = null
    runRecognitionTest(
      options =
        MapInteractions(MapInteractions.None) {
          camera { zoom { onStart { newer = checkNotNull(recorded).onGestureStarted() } } }
          bindings { transform { zoom { enabled = true } } }
        }
    ) { target ->
      recorded = target
      mapNode().performTouchInput {
        down(0, center - Offset(80f, 0f))
        down(1, center + Offset(80f, 0f))
        updatePointerBy(0, Offset(-60f, 0f))
        updatePointerBy(1, Offset(60f, 0f))
        move()
        up(0)
        up(1)
      }
      waitForIdle()
      assertTrue(target.scaleCalls.isEmpty())
      assertTrue(checkNotNull(newer).acceptsCommands)
      target.onGestureEnded(checkNotNull(newer))
    }
  }

  @Test
  fun symmetric_pinch_does_not_recognize_pan_from_individual_finger_displacement() =
    runRecognitionTest(
      options =
        MapInteractions {
          bindings { transform { zoom { enabled = false } } }
          bindings { transform { rotate { enabled = false } } }
          bindings { transform { tilt { enabled = false } } }
          bindings { twoFingerTap { enabled = false } }
        }
    ) { target ->
      mapNode().performTouchInput {
        down(0, center + Offset(-70f, 0f))
        down(1, center + Offset(70f, 0f))
        updatePointerTo(0, center + Offset(-100f, 0f))
        updatePointerTo(1, center + Offset(100f, 0f))
        move()
        updatePointerTo(0, center + Offset(-99f, 0f))
        updatePointerTo(1, center + Offset(101f, 0f))
        move()
        up(0)
        up(1)
      }
      waitForIdle()
      assertTrue(target.moveCalls.isEmpty(), "finger span bypassed centroid pan slop")
      assertEquals(0, target.startedCount, "symmetric pinch claimed a pan camera session")
    }

  @Test
  fun two_finger_tap_requests_a_zoom_out() = runRecognitionTest { target ->
    mapNode().performTouchInput {
      down(0, center - Offset(40f, 0f))
      down(1, center + Offset(40f, 0f))
      up(0)
      up(1)
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.any { it.scale < 1.0 } }
  }

  @Test
  fun quick_zoom_does_not_leak_its_first_tap() = runRecognitionTest { target ->
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
  fun horizontal_motion_disqualifies_quick_zoom() = runRecognitionTest { target ->
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
  fun tab_focuses_the_map_without_engaging_it() = runFocusTest { target, unconsumed ->
    onNodeWithTag(BEFORE_MAP_TAG).requestFocus()
    mapNode().performKeyInput { pressKey(Key.Tab) }
    mapNode().assertIsFocused()

    mapNode().performKeyInput { pressKey(Key.DirectionRight) }
    waitForIdle()

    assertEquals(0, target.moveCalls.size, "a direction key panned a map that no key engaged")
    assertTrue(Key.DirectionRight in unconsumed, "the map consumed the direction key")
  }

  @Test
  fun enter_engages_the_map_so_a_direction_key_pans() = runFocusTest { target, unconsumed ->
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
    runFocusTest { target, unconsumed ->
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
  fun back_disengages_a_map_that_a_key_engaged() = runFocusTest { target, unconsumed ->
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
  fun a_click_engages_the_map_without_consuming_back() = runFocusTest { target, unconsumed ->
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
    runFocusTest(
      options =
        MapInteractions {
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
    runFocusTest(
      options =
        MapInteractions {
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
    var options by mutableStateOf(MapInteractions.Standard)
    runFocusTest(optionsProvider = { options }) { target, unconsumed ->
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput {
        pressKey(Key.Enter)
        keyDown(Key.DirectionRight)
      }
      waitUntil(timeoutMillis = TIMEOUT) { target.moveCalls.size == 1 }
      runOnIdle { options = MapInteractions.None }
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
  fun replacing_a_held_chord_does_not_reinterpret_its_release() {
    var options by mutableStateOf(MapInteractions.Standard)
    runFocusTest(optionsProvider = { options }) { target, unconsumed ->
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput {
        pressKey(Key.Enter)
        keyDown(Key.DirectionRight)
      }
      waitUntil(timeoutMillis = TIMEOUT) { target.moveCalls.size == 1 }
      runOnIdle {
        options = MapInteractions {
          bindings { keys { mappings { on(Key.DirectionRight) { zoomIn() } } } }
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
      waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.size == 1 }
    }
  }

  @Test
  fun shifted_plus_zooms_but_an_extra_modifier_does_not_match() =
    runFocusTest { target, unconsumed ->
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput {
        pressKey(Key.Enter)
        withKeyDown(Key.ShiftLeft) {
          pressKey(Key.Equals)
          pressKey(Key.Plus)
        }
      }
      waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.size == 2 }
      assertFalse(Key.Equals in unconsumed)
      assertFalse(Key.Plus in unconsumed)
      map.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Equals) } }
      waitForIdle()
      assertEquals(2, target.scaleCalls.size)
      assertTrue(Key.Equals in unconsumed)
    }

  @Test
  fun losing_focus_clears_engagement_before_the_map_is_focused_again() =
    runFocusTest { target, unconsumed ->
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
    runFocusTest(
      options =
        MapInteractions(from = MapInteractions.None) { bindings { rotary { enabled = true } } },
      rotaryNotchPixels = Float.POSITIVE_INFINITY,
    ) { _, _ ->
      onNodeWithTag(BEFORE_MAP_TAG).requestFocus()
      mapNode().performKeyInput { pressKey(Key.Tab) }
      onNodeWithTag(AFTER_MAP_TAG).assertIsFocused()
    }

  @Test
  fun camera_takeover_during_a_held_key_suppresses_repeats_until_release() {
    runFocusTest { target, unconsumed ->
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput {
        pressKey(Key.Enter)
        keyDown(Key.DirectionRight)
      }
      waitForIdle()
      val moves = target.moveCalls.size
      lateinit var newer: CameraInputToken
      runOnIdle { newer = target.onGestureStarted() }
      map.performKeyInput {
        advanceEventTime(600)
        keyUp(Key.DirectionRight)
      }
      waitForIdle()
      assertEquals(moves, target.moveCalls.size)
      assertFalse(Key.DirectionRight in unconsumed)
      runOnIdle { target.onGestureEnded(newer) }
      map.performKeyInput { pressKey(Key.DirectionRight) }
      waitForIdle()
      assertEquals(moves + 1, target.moveCalls.size)
    }
  }

  @Test
  fun pair_to_single_pan_restarts_the_semantic_component_under_the_retained_session() {
    val starts = mutableListOf<CameraInputStart>()
    runRecognitionTest(
      options =
        MapInteractions {
          camera {
            pan {
              onStart { starts += it }
              momentum { enabled = false }
            }
          }
          bindings {
            transform {
              zoom { enabled = false }
              rotate { enabled = false }
              tilt { enabled = false }
            }
          }
        }
    ) { target ->
      val map = mapNode()
      map.performTouchInput {
        down(0, center - Offset(80f, 0f))
        down(1, center + Offset(80f, 0f))
        updatePointerBy(0, Offset(40f, 0f))
        updatePointerBy(1, Offset(40f, 0f))
        move()
        up(0)
        updatePointerBy(1, Offset(40f, 0f))
        move()
        up(1)
      }
      waitForIdle()
      assertEquals(2, starts.size)
      assertEquals(starts.first().sessionId, starts.last().sessionId)
      assertEquals(1, target.startedCount)
      assertEquals(1, target.endedCount)
    }
  }

  @Test
  fun no_click_subscribers_or_pairing_demand_leaves_an_ordinary_tap_to_the_parent() {
    var parentClicks = 0
    runRecognitionTest(
      options =
        MapInteractions {
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

  private fun assumeRotaryInjectionSupported() {
    assumeTrue(
      "Compose Skiko rotary injection is a no-op",
      System.getProperty("java.vm.name") == "Dalvik",
    )
  }

  @Test
  fun focused_rotary_zooms_in_both_directions_without_engagement_and_ends_its_burst() {
    assumeRotaryInjectionSupported()
    runFocusTest(
      options =
        MapInteractions(from = MapInteractions.None) {
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
    runFocusTest(rotaryNotchPixels = 24f) { target, _ ->
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
    runFocusTest(rotaryNotchPixels = 24f) { target, _ ->
      assumeRotaryInjectionSupported()
      mainClock.autoAdvance = false
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput { pressKey(Key.Enter) }
      map.performRotaryScrollInput { rotateToScrollVertically(24f) }
      map.performKeyInput { pressKey(Key.DirectionRight) }
      waitForIdle()
      assertEquals(1, target.moveCalls.size)
      assertEquals(2, target.startedCount)
      assertEquals(2, target.endedCount)
      map.performRotaryScrollInput { rotateToScrollVertically(24f) }
      assertEquals(3, target.startedCount)
      assertEquals(2, target.scaleCalls.size)
    }

  /**
   * Places the map between two focusables and records every key press or release that reaches the
   * parent, which is every one the map does not consume.
   */
  private fun runFocusTest(
    options: MapInteractions = MapInteractions.Standard,
    rotaryNotchPixels: Float = 0f,
    optionsProvider: () -> MapInteractions = { options },
    body: ComposeUiTest.(RecordingGestureTarget, List<Key>) -> Unit,
  ) = runPlainComposeUiTest {
    val target = fixture.target
    val unconsumed = mutableListOf<Key>()
    setContent {
      Row(
        Modifier.fillMaxSize().onKeyEvent {
          unconsumed += it.key
          false
        }
      ) {
        Box(Modifier.size(40.dp).testTag(BEFORE_MAP_TAG).focusable())
        Box(Modifier.size(200.dp)) {
          GestureHost(target, optionsProvider(), rotaryNotchPixels)
        }
        Box(Modifier.size(40.dp).testTag(AFTER_MAP_TAG).focusable())
      }
    }
    waitForIdle()
    body(target, unconsumed)
  }

  private fun runRecognitionTest(
    options: MapInteractions = MapInteractions.Standard,
    parentOnClick: (() -> Unit)? = null,
    parentOnLongClick: (() -> Unit)? = null,
    parentModifier: Modifier = Modifier,
    optionsProvider: () -> MapInteractions = { options },
    body: ComposeUiTest.(RecordingGestureTarget) -> Unit,
  ) = runPlainComposeUiTest {
    val target = fixture.target
    setContent {
      val host: @Composable () -> Unit = { GestureHost(target, optionsProvider()) }
      when {
        parentOnLongClick != null ->
          Box(
            parentModifier
              .fillMaxSize()
              .combinedClickable(onClick = {}, onLongClick = parentOnLongClick)
          ) {
            host()
          }
        parentOnClick != null ->
          Box(parentModifier.fillMaxSize().clickable(onClick = parentOnClick)) { host() }
        else -> Box(parentModifier.fillMaxSize()) { host() }
      }
    }
    waitForIdle()
    body(target)
  }

  private fun ComposeUiTest.awaitClicks(target: RecordingGestureTarget, count: Int) {
    waitUntil(timeoutMillis = TIMEOUT) { target.clicks == count }
  }

  /** Parent clickable nodes merge semantics. The map tag is only in the unmerged tree. */
  private fun ComposeUiTest.mapNode(): SemanticsNodeInteraction =
    onNodeWithTag(RECOGNITION_MAP_TAG, useUnmergedTree = true)

  private companion object {
    const val TIMEOUT = 5_000L
    const val FRAME_MILLIS = 16L
    const val SECOND_TAP_GAP_MILLIS = 80L
    val SCROLL_HOLD_MILLIS =
      MapInteractions.Standard.bindings.scroll.idleDuration.inWholeMilliseconds
  }
}

@Composable
private fun GestureHost(
  target: RecordingGestureTarget,
  options: MapInteractions,
  rotaryNotchPixels: Float = 0f,
) {
  SideEffect { target.updateConfiguration(options) }
  val density = LocalDensity.current
  val focusRequester = remember { FocusRequester() }
  val focus = remember { InputFocus {} }
  val environment = remember {
    InputEnvironment(
      contentDescription = "map",
      engaged = "engaged",
      notEngaged = "not engaged",
      indication = null,
    )
  }
  Box(
    Modifier.fillMaxSize()
      .testTag(RECOGNITION_MAP_TAG)
      .mapInput(
        target,
        target::capture,
        { it in target.clickFamilies },
        options,
        density,
        focusRequester,
        focus,
        environment,
        rotaryNotchPixels,
      )
  )
}

private fun Modifier.consumePointerEvents(
  pass: PointerEventPass,
  type: PointerEventType,
): Modifier =
  pointerInput(pass, type) {
    awaitPointerEventScope {
      while (true) {
        val event = awaitPointerEvent(pass)
        if (event.type == type) event.changes.forEach { it.consume() }
      }
    }
  }
