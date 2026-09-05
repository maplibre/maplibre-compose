package org.maplibre.compose.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assume.assumeTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.runPlainComposeUiTest
import org.maplibre.compose.style.scaledBy
import org.maplibre.compose.style.systemAnimatorDurationScale
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

private const val RECOGNITION_MAP_TAG = "recognition-map"
private const val BEFORE_MAP_TAG = "before-map"
private const val AFTER_MAP_TAG = "after-map"

/**
 * Gesture recognition and binding for [mapInput], hosted on a recording [GestureTarget].
 *
 * These cases do not create a MapLibre map. Native camera effects of moveBy, scaleBy, and
 * rotateAndPitchBy live in CameraMoveReportingTest.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalTestApi::class)
class MapInputRecognitionTest {
  @Test
  fun a_throwing_pan_delta_cancels_once_before_applying_the_camera_response() {
    val calls = mutableListOf<String>()
    lateinit var recorded: RecordingGestureTarget
    val failure =
      assertFailsWith<IllegalStateException> {
        runRecognitionTest(
          options =
            MapGestures {
              dragPan {
                onStart { calls += "start" }
                onDelta {
                  calls += "delta"
                  error("pan observer failed")
                }
                onCancel { calls += "cancel" }
              }
            }
        ) { target ->
          recorded = target
          mapNode().performTouchInput {
            down(Offset(30f, 30f))
            moveBy(Offset(40f, 0f))
            up()
          }
          waitForIdle()
        }
      }
    assertEquals("pan observer failed", failure.message)
    assertEquals(listOf("start", "delta", "cancel"), calls)
    assertTrue(recorded.moveCalls.isEmpty())
    assertEquals(1, recorded.startedCount)
    assertEquals(1, recorded.endedCount)
  }

  @Test
  fun a_throwing_end_observer_cancels_the_custom_preview_without_a_second_observer_terminal() {
    val observed = mutableListOf<String>()
    val response = mutableListOf<DragEvent>()
    lateinit var recorded: RecordingGestureTarget
    val failure =
      assertFailsWith<IllegalStateException> {
        runRecognitionTest(
          options =
            MapGestures {
              drag("edit") {
                onEnd {
                  observed += "end"
                  error("end observer failed")
                }
                onCancel { observed += "cancel" }
                action = DragAction.Custom { response += it }
              }
            }
        ) { target ->
          recorded = target
          mapNode().performTouchInput {
            down(Offset(30f, 30f))
            moveBy(Offset(40f, 0f))
            up()
          }
          waitForIdle()
        }
      }
    assertEquals("end observer failed", failure.message)
    assertEquals(listOf("end"), observed)
    assertTrue(response.first() is DragEvent.Start)
    assertEquals(1, response.count { it is DragEvent.Cancel })
    assertFalse(response.any { it is DragEvent.End })
    assertEquals(1, recorded.endedCount)
  }

  @Test
  fun a_throwing_pair_end_observer_cancels_other_started_components_once() {
    val observed = mutableListOf<String>()
    lateinit var recorded: RecordingGestureTarget
    val failure =
      assertFailsWith<IllegalStateException> {
        runRecognitionTest(
          options =
            MapGestures {
              dragPan {
                onEnd {
                  observed += "pan end"
                  error("pair end failed")
                }
                onCancel { observed += "pan cancel" }
              }
              pinchZoom {
                onStart { observed += "pinch start" }
                onEnd { observed += "pinch end" }
                onCancel { observed += "pinch cancel" }
              }
            }
        ) { target ->
          recorded = target
          mapNode().performTouchInput {
            down(0, Offset(40f, 100f))
            down(1, Offset(200f, 100f))
            updatePointerTo(0, Offset(50f, 100f))
            updatePointerTo(1, Offset(250f, 100f))
            move(delayMillis = 32)
            up(0)
            up(1)
          }
          waitForIdle()
        }
      }
    assertEquals("pair end failed", failure.message)
    assertEquals(listOf("pinch start", "pan end", "pinch cancel"), observed)
    assertEquals(1, recorded.startedCount)
    assertEquals(1, recorded.endedCount)
  }

  @Test
  fun a_throwing_scroll_end_observer_closes_the_session_without_a_second_terminal() {
    val observed = mutableListOf<String>()
    lateinit var recorded: RecordingGestureTarget
    val failure =
      assertFailsWith<IllegalStateException> {
        runRecognitionTest(
          options =
            MapGestures {
              scrollZoom {
                onEnd {
                  observed += "end"
                  error("scroll end failed")
                }
                onCancel { observed += "cancel" }
              }
            }
        ) { target ->
          recorded = target
          mapNode().performMouseInput {
            moveTo(Offset(40f, 40f))
            scroll(1f)
          }
          mainClock.advanceTimeBy(300)
          waitForIdle()
        }
      }
    assertEquals("scroll end failed", failure.message)
    assertEquals(listOf("end"), observed)
    assertEquals(1, recorded.scaleCalls.size)
    assertEquals(1, recorded.startedCount)
    assertEquals(1, recorded.endedCount)
  }

  @Test
  fun trackpad_pan_and_scale_route_through_the_pointer_node_without_clicks_or_momentum() {
    val pans = mutableListOf<DragEvent>()
    val pinches = mutableListOf<PinchEvent>()
    runRecognitionTest(
      options =
        MapGestures {
          dragPan {
            onStart { pans += it }
            onDelta { pans += it }
            onEnd { pans += it }
          }
          pinchZoom {
            onStart { pinches += it }
            onDelta { pinches += it }
            onEnd { pinches += it }
          }
        }
    ) { target ->
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
      assertEquals(3, pans.size)
      assertEquals(3, pinches.size)
      assertEquals(1, target.moveCalls.size)
      assertEquals(1.5, target.scaleCalls.single().scale, 1e-6)
      assertEquals(1, target.startedCount)
      assertEquals(1, target.endedCount)
      mainClock.advanceTimeBy(500)
      waitForIdle()
      assertEquals(1, target.moveCalls.size)
      assertEquals(1, target.scaleCalls.size)
      assertEquals(0, target.clicks)
    }
  }

  @Test
  fun a_structural_restart_suppresses_trackpad_changes_until_the_old_component_ends() {
    val terminals = mutableListOf<GestureCancellationReason>()
    var options by mutableStateOf(MapGestures { pinchZoom { onCancel { terminals += it.reason } } })
    runRecognitionTest(optionsProvider = { options }) { target ->
      val map = mapNode()
      map.performTrackpadInput {
        moveTo(Offset(80f, 80f))
        scaleStart()
        scaleChangeBy(1.5f)
      }
      runOnIdle { options = MapGestures { pinchZoom { zoomScale = 2.0 } } }
      waitForIdle()
      map.performTrackpadInput {
        scaleChangeBy(1.5f)
        scaleEnd()
      }
      waitForIdle()
      assertEquals(listOf(GestureCancellationReason.ConfigurationChanged), terminals)
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
        MapGestures.Standard.animationDuration.scaledBy(systemAnimatorDurationScale()),
        target.fitCalls.single().second,
      )
      assertEquals(1, target.startedCount)
      assertEquals(1, target.endedCount)
      assertTrue(target.moveCalls.isEmpty())
      assertEquals(0, target.clicks)
    }

  @Test
  fun cancelling_box_zoom_clears_the_preview_without_fitting() {
    var configuration by mutableStateOf(MapGestures.Standard)
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
      runOnIdle { configuration = MapGestures.None }
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
  fun a_mouse_press_exits_hover_before_drag_slop_and_release_can_reenter() {
    val events = mutableListOf<HoverEvent>()
    runRecognitionTest(options = MapGestures { hover { onEvent { events += it } } }) { target ->
      val map = mapNode()
      map.performMouseInput {
        moveTo(center)
        press()
      }
      waitForIdle()
      assertTrue(events.first() is HoverEvent.Enter)
      assertTrue(events.last() is HoverEvent.Exit)
      assertEquals(1, events.count { it is HoverEvent.Enter })
      assertEquals(1, events.count { it is HoverEvent.Exit })
      assertTrue(target.moveCalls.isEmpty())
      map.performMouseInput {
        release()
        moveBy(Offset(5f, 0f))
      }
      waitForIdle()
      assertEquals(2, events.count { it is HoverEvent.Enter })
      assertTrue(events.last() is HoverEvent.Move)
    }
  }

  @Test
  fun touchscreen_input_never_reports_hover() {
    val events = mutableListOf<HoverEvent>()
    runRecognitionTest(options = MapGestures { hover { onEvent { events += it } } }) { _ ->
      mapNode().performTouchInput {
        down(center)
        moveBy(Offset(5f, 0f))
        up()
      }
      waitForIdle()
      assertTrue(events.isEmpty())
    }
  }

  @Test
  fun two_finger_tap_only_claims_its_press_against_a_parent_click() {
    var taps = 0
    var parentClicks = 0
    runRecognitionTest(
      options =
        MapGestures(MapGestures.None) {
          twoFingerTap {
            enabled = true
            onEvent {
              taps++
              ClickResult.Consume
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
      assertEquals(1, taps)
      assertEquals(0, parentClicks)
      assertTrue(target.scaleCalls.isEmpty())
    }
  }

  @Test
  fun consuming_a_double_tap_binding_suppresses_zoom_and_map_delivery() {
    val doubles = mutableListOf<DoubleTapEvent>()
    runRecognitionTest(
      options =
        MapGestures {
          doubleTap {
            onEvent {
              doubles += it
              ClickResult.Consume
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
  fun a_secondary_mouse_click_retains_its_button_in_long_press_metadata() {
    val events = mutableListOf<LongPressEvent>()
    runRecognitionTest(
      options =
        MapGestures {
          longPress {
            onEvent {
              events += it
              ClickResult.Pass
            }
          }
        }
    ) { target ->
      mapNode().performMouseInput { click(center, MouseButton.Secondary) }
      waitForIdle()
      assertEquals(setOf(PointerButton.Secondary), events.single().buttons)
      assertEquals(1, target.longClicks)
      assertEquals(listOf(TapFamily.LongPress), target.deliveredTapFamilies)
    }
  }

  @Test
  fun a_double_tap_slot_without_a_dispatch_path_does_not_delay_touch_clicks() {
    runRecognitionTest(
      options =
        MapGestures {
          doubleTap { cameraAction = null }
          quickZoom { enabled = false }
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
  fun layer_double_tap_demand_is_snapshotted_for_the_contact_sequence() {
    runRecognitionTest(
      options =
        MapGestures {
          doubleTap { cameraAction = null }
          quickZoom { enabled = false }
        }
    ) { target ->
      target.capabilities = setOf(TapFamily.Tap, TapFamily.DoubleTap)
      val map = mapNode()
      map.performTouchInput { down(center) }
      target.capabilities = setOf(TapFamily.Tap)
      map.performTouchInput {
        up()
        advanceEventTime(SECOND_TAP_GAP_MILLIS)
        click(center)
      }
      waitForIdle()
      assertEquals(listOf(TapFamily.DoubleTap), target.deliveredTapFamilies)
      assertTrue(target.scaleCalls.isEmpty())
    }
  }

  @Test
  fun consuming_the_first_mouse_click_does_not_consume_the_later_double_click() {
    runRecognitionTest(
      options =
        MapGestures {
          tap { onEvent { ClickResult.Consume } }
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
        MapGestures(MapGestures.None) {
          quickZoom {
            enabled = true
            continuation = null
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
  fun consuming_two_finger_tap_dispatch_suppresses_its_camera_action() {
    var taps = 0
    runRecognitionTest(
      options =
        MapGestures {
          twoFingerTap {
            onEvent {
              taps++
              ClickResult.Consume
            }
          }
        }
    ) { target ->
      mapNode().performTouchInput {
        down(0, center - Offset(40f, 0f))
        down(1, center + Offset(40f, 0f))
        up(0)
        up(1)
      }
      waitForIdle()
      assertEquals(1, taps)
      assertTrue(target.scaleCalls.isEmpty())
    }
  }

  @Test
  fun an_unmatched_contact_does_not_cancel_an_active_scroll_burst() {
    val cancelled = mutableListOf<GestureCancellationReason>()
    runRecognitionTest(
      options =
        MapGestures(MapGestures.None) {
          scrollZoom {
            enabled = true
            onCancel { cancelled += it.reason }
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
        assertTrue(cancelled.isEmpty())
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
  fun observer_takeover_cancels_before_final_pass_consumption_can_replace_the_reason() {
    var recorded: RecordingGestureTarget? = null
    var newer: GestureToken? = null
    val cancelled = mutableListOf<GestureCancellationReason>()
    runRecognitionTest(
      options =
        MapGestures {
          dragPan {
            onStart { newer = checkNotNull(recorded).onGestureStarted() }
            onCancel { cancelled += it.reason }
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
      assertEquals(listOf(GestureCancellationReason.CameraTakeover), cancelled)
      assertTrue(target.moveCalls.isEmpty())
      assertTrue(checkNotNull(newer).acceptsCommands)
      target.onGestureEnded(checkNotNull(newer))
    }
  }

  @Test
  fun key_takeover_cancels_a_held_drag_without_waiting_for_another_pointer_event() {
    val cancelled = mutableListOf<GestureCancellationReason>()
    runRecognitionTest(options = MapGestures { dragPan { onCancel { cancelled += it.reason } } }) {
      target ->
      val map = mapNode()
      map.performTouchInput {
        down(center)
        moveBy(Offset(30f, 0f))
      }
      waitForIdle()
      map.performKeyInput { pressKey(Key.DirectionRight) }
      waitForIdle()
      assertEquals(listOf(GestureCancellationReason.CameraTakeover), cancelled)
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
  fun standard_scroll_pans_continuous_input_and_holds_that_estimate_for_the_burst() =
    runRecognitionTest { target ->
      mainClock.autoAdvance = false
      try {
        val map = mapNode()
        map.performMouseInput {
          scroll(0.25f)
          scroll(1f)
        }
        waitForIdle()
        assertEquals(2, target.moveCalls.size)
        assertTrue(target.scaleCalls.isEmpty())
        mainClock.advanceTimeBy(SCROLL_HOLD_MILLIS + FRAME_MILLIS)
        map.performMouseInput { scroll(1f) }
        waitForIdle()
        assertEquals(1, target.scaleCalls.size)
      } finally {
        mainClock.autoAdvance = true
      }
    }

  @Test
  fun scroll_lifecycle_observes_the_selected_binding_and_balances_its_burst() {
    val events = mutableListOf<ScrollEvent>()
    runRecognitionTest(
      options =
        MapGestures {
          scrollPan {
            onStart { events += it }
            onDelta { events += it }
            onEnd { events += it }
          }
        }
    ) { target ->
      mapNode().performMouseInput { scroll(Offset(1f, 2f)) }
      mainClock.advanceTimeBy(SCROLL_HOLD_MILLIS + FRAME_MILLIS)
      waitForIdle()
      assertEquals(3, events.size)
      assertTrue(events[0] is ScrollEvent.Start)
      assertTrue(events[1] is ScrollEvent.Delta)
      assertTrue(events[2] is ScrollEvent.End)
      assertEquals(1, events.map { it.gestureId }.distinct().size)
      assertTrue(events.all { it.kind == ScrollKind.Continuous })
      assertTrue(target.scaleCalls.isEmpty())
      assertEquals(1, target.moveCalls.size)
    }
  }

  @Test
  fun a_buttonless_scroll_does_not_match_an_explicit_primary_button_filter() =
    runRecognitionTest(
      options =
        MapGestures(from = MapGestures.None) {
          scrollZoom {
            enabled = true
            filter = PointerFilter()
          }
        }
    ) { target ->
      mapNode().performMouseInput { scroll(-1f) }
      waitForIdle()
      assertTrue(target.scaleCalls.isEmpty())
      assertEquals(0, target.startedCount)
    }

  @Test
  fun none_leaves_clicks_and_focus_to_the_parent() {
    var parentClicks = 0
    runRecognitionTest(options = MapGestures.None, parentOnClick = { parentClicks++ }) { target ->
      mapNode().performMouseInput { click(center) }
      waitForIdle()
      assertEquals(1, parentClicks)
      assertEquals(0, target.clicks)
      assertEquals(0, target.startedCount)
      mapNode().assert(expectValue(SemanticsProperties.StateDescription, "not engaged"))
    }
  }

  @Test
  fun a_custom_reservation_waits_for_its_own_slop_and_observes_before_response() {
    val delivered = mutableListOf<DragEvent>()
    var claims = 0
    val options = MapGestures {
      drag(id = "handle") {
        startSlop = 40.dp
        canStart {
          claims++
          true
        }
        action = DragAction.Custom { delivered += it }
      }
    }
    runRecognitionTest(options = options) { target ->
      val map = mapNode()
      map.performTouchInput {
        down(center)
        moveBy(Offset(10f, 0f))
      }
      waitForIdle()
      assertTrue(delivered.isEmpty())
      assertTrue(target.moveCalls.isEmpty())
      map.performTouchInput {
        moveBy(Offset(90f, 0f))
        up()
      }
      waitForIdle()
      assertEquals(1, claims)
      assertTrue(delivered.first() is DragEvent.Start)
      assertTrue(delivered[1] is DragEvent.Delta)
      assertTrue(delivered.last() is DragEvent.End)
      assertEquals(1, delivered.map { it.gestureId }.distinct().size)
      assertTrue(target.moveCalls.isEmpty())
    }
  }

  @Test
  fun takeover_in_an_end_observer_cancels_the_started_custom_response_once() {
    var recorded: RecordingGestureTarget? = null
    var newer: GestureToken? = null
    val observed = mutableListOf<DragEvent>()
    val delivered = mutableListOf<DragEvent>()
    runRecognitionTest(
      options =
        MapGestures {
          drag(id = "handle") {
            onEnd {
              observed += it
              newer = checkNotNull(recorded).onGestureStarted()
            }
            onCancel { observed += it }
            action = DragAction.Custom { delivered += it }
          }
        }
    ) { target ->
      recorded = target
      mapNode().performTouchInput {
        down(center)
        moveBy(Offset(60f, 0f))
        up()
      }
      waitForIdle()
      assertEquals(1, observed.count { it is DragEvent.End })
      assertEquals(0, observed.count { it is DragEvent.Cancel })
      assertEquals(1, delivered.count { it is DragEvent.Start })
      assertEquals(0, delivered.count { it is DragEvent.End })
      assertEquals(1, delivered.count { it is DragEvent.Cancel })
      assertEquals(
        GestureCancellationReason.CameraTakeover,
        (delivered.last() as DragEvent.Cancel).reason,
      )
      assertTrue(checkNotNull(newer).acceptsCommands)
      target.onGestureEnded(checkNotNull(newer))
    }
  }

  @Test
  fun takeover_before_the_custom_response_starts_does_not_deliver_an_orphan_cancel() {
    var recorded: RecordingGestureTarget? = null
    var newer: GestureToken? = null
    val delivered = mutableListOf<DragEvent>()
    runRecognitionTest(
      options =
        MapGestures {
          drag(id = "handle") {
            onStart { newer = checkNotNull(recorded).onGestureStarted() }
            action = DragAction.Custom { delivered += it }
          }
        }
    ) { target ->
      recorded = target
      mapNode().performTouchInput {
        down(center)
        moveBy(Offset(60f, 0f))
        up()
      }
      waitForIdle()
      assertTrue(delivered.isEmpty())
      assertTrue(checkNotNull(newer).acceptsCommands)
      target.onGestureEnded(checkNotNull(newer))
    }
  }

  @Test
  fun a_rejected_custom_claim_allows_the_next_drag() {
    var customEvents = 0
    runRecognitionTest(
      options =
        MapGestures {
          drag(id = "handle") {
            canStart { false }
            action = DragAction.Custom { customEvents++ }
          }
        }
    ) { target ->
      mapNode().performTouchInput {
        down(center)
        moveBy(Offset(60f, 0f))
        up()
      }
      waitForIdle()
      assertTrue(target.moveCalls.isNotEmpty())
      assertEquals(0, customEvents)
    }
  }

  @Test
  fun adding_a_contact_cancels_a_custom_drag_and_suppresses_the_group() {
    val delivered = mutableListOf<DragEvent>()
    runRecognitionTest(
      options =
        MapGestures {
          drag(id = "handle") { action = DragAction.Custom { delivered += it } }
        }
    ) { target ->
      mapNode().performTouchInput {
        down(0, center)
        moveBy(Offset(60f, 0f))
        down(1, center + Offset(-60f, 0f))
        moveBy(0, Offset(30f, 0f))
        up(1)
        moveBy(0, Offset(30f, 0f))
        up(0)
      }
      waitForIdle()
      val cancel = delivered.last() as DragEvent.Cancel
      assertEquals(GestureCancellationReason.BindingChanged, cancel.reason)
      assertEquals(1, delivered.count { it is DragEvent.Cancel })
      assertEquals(0, delivered.count { it is DragEvent.End })
      assertTrue(target.moveCalls.isEmpty())
      assertTrue(target.scaleCalls.isEmpty())
    }
  }

  @Test
  fun changing_only_a_callback_updates_delivery_without_restarting_the_drag() {
    val callbacks = mutableListOf<String>()
    var configuration by mutableStateOf(MapGestures { dragPan { onDelta { callbacks += "old" } } })
    runRecognitionTest(optionsProvider = { configuration }) { target ->
      val map = mapNode()
      map.performTouchInput {
        down(center)
        moveBy(Offset(30f, 0f))
      }
      waitForIdle()
      runOnIdle { configuration = MapGestures { dragPan { onDelta { callbacks += "new" } } } }
      map.performTouchInput {
        moveBy(Offset(30f, 0f))
        up()
      }
      waitForIdle()
      assertEquals(listOf("old", "new"), callbacks)
      assertEquals(1, target.startedCount)
    }
  }

  @Test
  fun structural_replacement_cancels_the_previous_custom_response_with_its_latest_callback() {
    val callbacks = mutableListOf<Pair<String, DragEvent>>()
    fun configuration(name: String, slop: Int) = MapGestures {
      drag(id = "handle") {
        startSlop = slop.dp
        action = DragAction.Custom { callbacks += name to it }
      }
    }
    var options by mutableStateOf(configuration("initial", 4))
    runRecognitionTest(optionsProvider = { options }) {
      val map = mapNode()
      map.performTouchInput {
        down(center)
        moveBy(Offset(40f, 0f))
      }
      runOnIdle { options = configuration("updated", 4) }
      waitForIdle()
      runOnIdle { options = configuration("replacement", 8) }
      waitForIdle()
      val cancellation = callbacks.single { it.second is DragEvent.Cancel }
      assertEquals("updated", cancellation.first)
      assertEquals(
        GestureCancellationReason.ConfigurationChanged,
        (cancellation.second as DragEvent.Cancel).reason,
      )
      assertFalse(callbacks.any { it.first == "replacement" })
      map.performTouchInput {
        up()
        down(center)
        moveBy(Offset(40f, 0f))
        up()
      }
      waitForIdle()
      assertTrue(callbacks.last().second is DragEvent.End)
      assertEquals("replacement", callbacks.last().first)
    }
  }

  @Test
  fun a_structural_change_cancels_the_drag_and_waits_for_existing_contacts_to_lift() {
    val cancellations = mutableListOf<GestureCancellationReason>()
    var configuration by
      mutableStateOf(MapGestures { dragPan { onCancel { cancellations += it.reason } } })
    runRecognitionTest(optionsProvider = { configuration }) { target ->
      val map = mapNode()
      map.performTouchInput {
        down(center)
        moveBy(Offset(30f, 0f))
      }
      waitForIdle()
      val moves = target.moveCalls.size
      runOnIdle {
        configuration = MapGestures(from = configuration) { dragPan { startSlop = 8.dp } }
      }
      map.performTouchInput {
        moveBy(Offset(30f, 0f))
        up()
      }
      waitForIdle()
      assertEquals(listOf(GestureCancellationReason.ConfigurationChanged), cancellations)
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
  fun a_pan_observer_runs_before_its_first_camera_command() {
    var recorded: RecordingGestureTarget? = null
    val counts = mutableListOf<Int>()
    runRecognitionTest(
      options =
        MapGestures {
          dragPan {
            onStart { counts += checkNotNull(recorded).moveCalls.size }
            onDelta { counts += checkNotNull(recorded).moveCalls.size }
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
      assertEquals(listOf(0, 0), counts)
      assertTrue(target.moveCalls.isNotEmpty())
    }
  }

  @Test
  fun changing_scroll_modifiers_cancels_the_old_burst_before_starting_another() =
    runRecognitionTest { target ->
      mainClock.autoAdvance = false
      val map = mapNode()
      try {
        map.requestFocus()
        map.performMouseInput { scroll(-1f) }
        map.performKeyInput { keyDown(Key.CtrlLeft) }
        map.performMouseInput { scroll(-1f) }
        waitForIdle()
        assertEquals(2, target.startedCount)
        assertEquals(1, target.endedCount)
        assertEquals(2, target.scaleCalls.size)
      } finally {
        map.performKeyInput { keyUp(Key.CtrlLeft) }
        mainClock.autoAdvance = true
      }
    }

  @Test
  fun scroll_is_claimed_during_main_before_the_parent_observes_it() {
    var parentSawConsumed = false
    runRecognitionTest(
      options = MapGestures { scrollPan { enabled = false } },
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
      mapNode().performMouseInput { scroll(Offset(2f, -1f)) }
      waitForIdle()
      assertTrue(parentSawConsumed)
      assertEquals(1, target.scaleCalls.size)
      assertTrue(
        target.scaleCalls.single().scale < 1.0,
        "horizontal dominance lost the zoom direction",
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
        MapGestures {
          doubleTap { enabled = false }
          quickZoom { enabled = false }
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
        MapGestures {
          doubleTap { enabled = false }
          quickZoom { enabled = false }
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
  fun position_locked_zooms_about_the_centre() =
    runRecognitionTest(options = MapGestures.PositionLocked) { target ->
      mapNode().performMouseInput { doubleClick(Offset(width * 0.2f, height * 0.2f)) }
      waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.isNotEmpty() }
      assertTrue(
        target.scaleCalls.all { it.anchor == null },
        "a locked zoom anchored at the pointer",
      )
    }

  @Test
  fun arrow_keys_request_a_pan() = runRecognitionTest { target ->
    val map = mapNode()
    map.performMouseInput { click(Offset(10f, 10f)) }
    map.performKeyInput { pressKey(Key.DirectionRight) }
    waitUntil(timeoutMillis = TIMEOUT) { target.moveCalls.isNotEmpty() }
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
    runRecognitionTest(options = MapGestures { scrollIdleDuration = 600.milliseconds }) { target ->
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
  }

  @Test
  fun pair_pan_uses_its_slop_and_ends_when_the_first_contact_lifts() {
    val events = mutableListOf<DragEvent>()
    runRecognitionTest(
      options =
        MapGestures(MapGestures.None) {
          dragPan {
            enabled = true
            startSlop = 20.dp
            continuation = null
            onStart { events += it }
            onDelta { events += it }
            onEnd { events += it }
            onCancel { events += it }
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
      val start = events.first() as DragEvent.Start
      val delta = events[1] as DragEvent.Delta
      assertEquals(
        start.screenOffset.x.value - start.startOffset.x.value - 20f,
        delta.delta.x.value,
        0.001f,
      )
      assertTrue(events.last() is DragEvent.End)
      assertEquals(1, events.map { it.gestureId }.distinct().size)
      assertEquals(0, target.endedCount, "the remaining contact lost the shared camera session")
      map.performTouchInput { up(1) }
      waitForIdle()
      assertEquals(1, events.count { it is DragEvent.End })
      assertEquals(1, target.endedCount)
    }
  }

  @Test
  fun pair_bindings_reject_unmatched_filters() {
    var starts = 0
    runRecognitionTest(
      options =
        MapGestures(MapGestures.None) {
          pinchZoom {
            enabled = true
            filter = PointerFilter(modifiers = ModifierFilter.Containing(KeyModifier.Ctrl))
            onStart { starts++ }
          }
        }
    ) { target ->
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
      assertEquals(0, starts)
      assertEquals(0, target.startedCount)
      assertTrue(target.scaleCalls.isEmpty())
    }
  }

  @Test
  fun pair_pinch_subtracts_configured_span_slop_before_its_first_delta() {
    val events = mutableListOf<PinchEvent>()
    var centerX = 0f
    runRecognitionTest(
      options =
        MapGestures(MapGestures.None) {
          pinchZoom {
            enabled = true
            startSpanSlop = 40.dp
            anchor = GestureAnchor.CameraCenter
            continuation = null
            onStart { events += it }
            onDelta { events += it }
            onEnd { events += it }
          }
        }
    ) { target ->
      mapNode().performTouchInput {
        centerX = center.x
        down(0, center - Offset(80f, 0f))
        down(1, center + Offset(80f, 0f))
        updatePointerBy(0, Offset(-60f, 0f))
        updatePointerBy(1, Offset(60f, 0f))
        move()
        up(0)
        up(1)
      }
      waitForIdle()
      val start = events.first() as PinchEvent.Start
      val delta = events[1] as PinchEvent.Delta
      val density = centerX / start.screenOffset.x.value
      val expected = 280.0 / (160.0 + 40.0 * density / 2)
      assertEquals(expected, delta.scaleFactor, 0.00001)
      assertEquals(GestureMath.pinchScale(expected), target.scaleCalls.single().scale, 0.00001)
      assertEquals(null, target.scaleCalls.single().anchor)
      assertTrue(events.last() is PinchEvent.End)
    }
  }

  @Test
  fun a_shove_cancels_the_started_pair_pan_before_tilt() {
    val order = mutableListOf<String>()
    runRecognitionTest(
      options =
        MapGestures {
          dragPan {
            continuation = null
            onStart { order += "pan start" }
            onEnd { order += "pan end" }
            onCancel { order += "pan cancel ${it.reason}" }
          }
          twoFingerTilt {
            continuation = null
            onStart { order += "tilt start" }
            onEnd { order += "tilt end" }
          }
        }
    ) { target ->
      mapNode().performTouchInput {
        down(0, center - Offset(80f, 0f))
        down(1, center + Offset(80f, 0f))
        repeat(16) {
          updatePointerBy(0, Offset(0f, -5f))
          updatePointerBy(1, Offset(0f, -5f))
          move(delayMillis = 20)
        }
        up(0)
        up(1)
      }
      waitForIdle()
      assertEquals(
        listOf("pan start", "pan cancel BindingChanged", "tilt start", "tilt end"),
        order,
      )
      assertEquals(1, target.startedCount)
      assertTrue(target.rotateCalls.any { it.pitchDelta != 0.0 })
    }
  }

  @Test
  fun rotation_cancels_pinch_and_additional_span_starts_a_new_pinch() {
    val pinches = mutableListOf<PinchEvent>()
    val rotations = mutableListOf<RotateEvent>()
    runRecognitionTest(
      options =
        MapGestures {
          dragPan { enabled = false }
          twoFingerTilt { enabled = false }
          pinchZoom {
            continuation = null
            onStart { pinches += it }
            onCancel { pinches += it }
            onEnd { pinches += it }
          }
          twoFingerRotate {
            continuation = null
            onStart { rotations += it }
            onEnd { rotations += it }
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
      assertEquals(2, pinches.count { it is PinchEvent.Start })
      assertEquals(1, pinches.count { it is PinchEvent.Cancel })
      assertEquals(1, pinches.count { it is PinchEvent.End })
      assertEquals(2, pinches.map { it.gestureId }.distinct().size)
      assertEquals(2, rotations.size)
      assertTrue(rotations.first() is RotateEvent.Start)
      assertTrue(rotations.last() is RotateEvent.End)
      assertEquals(1, target.startedCount)
    }
  }

  @Test
  fun replacing_a_selected_contact_ends_the_old_pair_and_rebases_the_new_pair() {
    val events = mutableListOf<DragEvent>()
    runRecognitionTest(
      options =
        MapGestures(MapGestures.None) {
          dragPan {
            enabled = true
            continuation = null
            onStart { events += it }
            onEnd { events += it }
            onCancel { events += it }
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
      assertEquals(1, events.count { it is DragEvent.End })
      map.performTouchInput {
        updatePointerBy(0, Offset(40f, 0f))
        updatePointerBy(2, Offset(40f, 0f))
        move()
        up(0)
        up(2)
      }
      waitForIdle()
      assertEquals(2, events.count { it is DragEvent.Start })
      assertEquals(2, events.count { it is DragEvent.End })
      assertTrue(events.none { it is DragEvent.Cancel })
      assertEquals(2, events.map { it.gestureId }.distinct().size)
      assertEquals(1, target.startedCount)
      assertEquals(1, target.endedCount)
    }
  }

  @Test
  fun newly_recognized_single_pan_discards_the_previous_pairs_staged_momentum() {
    runRecognitionTest(
      options =
        MapGestures(MapGestures.None) {
          dragPan {
            enabled = true
            continuation = Fling(minimumSpeed = 1.0)
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
        MapGestures(MapGestures.None) {
          dragPan {
            enabled = true
            continuation = Fling(minimumSpeed = 1.0)
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
  fun pair_observer_takeover_cancels_all_started_components_before_response() {
    var recorded: RecordingGestureTarget? = null
    var newer: GestureToken? = null
    val events = mutableListOf<PinchEvent>()
    runRecognitionTest(
      options =
        MapGestures(MapGestures.None) {
          pinchZoom {
            enabled = true
            onStart {
              events += it
              newer = checkNotNull(recorded).onGestureStarted()
            }
            onDelta { events += it }
            onCancel { events += it }
          }
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
      assertEquals(2, events.size)
      assertTrue(events.first() is PinchEvent.Start)
      assertEquals(
        GestureCancellationReason.CameraTakeover,
        (events.last() as PinchEvent.Cancel).reason,
      )
      assertTrue(target.scaleCalls.isEmpty())
      assertTrue(checkNotNull(newer).acceptsCommands)
      target.onGestureEnded(checkNotNull(newer))
    }
  }

  @Test
  fun pinch_requests_a_scale() = runRecognitionTest { target ->
    mapNode().performTouchInput {
      pinch(
        start0 = center - Offset(30f, 0f),
        start1 = center + Offset(30f, 0f),
        end0 = center - Offset(120f, 0f),
        end1 = center + Offset(120f, 0f),
        durationMillis = 200,
      )
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.any { it.scale > 1.0 } }
  }

  @Test
  fun symmetric_pinch_does_not_recognize_pan_from_individual_finger_displacement() =
    runRecognitionTest(
      options =
        MapGestures {
          pinchZoom { enabled = false }
          twoFingerRotate { enabled = false }
          twoFingerTilt { enabled = false }
          twoFingerTap { enabled = false }
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
  fun two_finger_rotation_requests_bearing() = runRecognitionTest { target ->
    mapNode().performTouchInput {
      down(0, center - Offset(80f, 0f))
      down(1, center + Offset(80f, 0f))
      updatePointerTo(0, center - Offset(0f, 80f))
      updatePointerTo(1, center + Offset(0f, 80f))
      move()
      up(0)
      up(1)
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.rotateCalls.any { it.bearingDelta != 0.0 } }
  }

  @Test
  fun two_finger_shove_requests_tilt() = runRecognitionTest { target ->
    mapNode().performTouchInput {
      down(0, center - Offset(80f, 0f))
      down(1, center + Offset(80f, 0f))
      // Cross pan's 4 dp threshold before shove's 16 dp threshold, just as a real stream does.
      repeat(5) {
        updatePointerBy(0, Offset(0f, -5f))
        updatePointerBy(1, Offset(0f, -5f))
        move(delayMillis = 20)
      }
      up(0)
      up(1)
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.rotateCalls.any { it.pitchDelta != 0.0 } }
    assertTrue(target.rotateCalls.all { it.bearingDelta == 0.0 }, "a shove also rotated")
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
  fun one_finger_swipe_requests_a_pan() = runRecognitionTest { target ->
    mapNode().performTouchInput { swipe(center, center + Offset(80f, 0f), durationMillis = 100) }
    waitUntil(timeoutMillis = TIMEOUT) { target.moveCalls.isNotEmpty() }
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
        MapGestures {
          keys {
            clearPan()
            clearZoom()
            clearRotate()
            clearTilt()
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
        MapGestures {
          keys {
            clearPan()
            clearZoom()
            clearRotate()
            clearTilt()
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
    var options by mutableStateOf(MapGestures.Standard)
    runFocusTest(optionsProvider = { options }) { target, unconsumed ->
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput {
        pressKey(Key.Enter)
        keyDown(Key.DirectionRight)
      }
      waitUntil(timeoutMillis = TIMEOUT) { target.moveCalls.size == 1 }
      runOnIdle { options = MapGestures.None }
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
    var options by mutableStateOf(MapGestures.Standard)
    runFocusTest(optionsProvider = { options }) { target, unconsumed ->
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput {
        pressKey(Key.Enter)
        keyDown(Key.DirectionRight)
      }
      waitUntil(timeoutMillis = TIMEOUT) { target.moveCalls.size == 1 }
      runOnIdle {
        options = MapGestures {
          keys { bind(KeyChord(Key.DirectionRight), GestureKeyAction.ZoomIn) }
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
  fun key_observer_replacement_is_used_without_losing_engagement() {
    val observed = mutableListOf<String>()
    var options by mutableStateOf(MapGestures { keys { onEvent { observed += "initial" } } })
    runFocusTest(optionsProvider = { options }) { target, _ ->
      val map = mapNode()
      map.requestFocus()
      map.performKeyInput { pressKey(Key.Enter) }
      runOnIdle { options = MapGestures { keys { onEvent { observed += "updated" } } } }
      waitForIdle()
      map.performKeyInput { pressKey(Key.DirectionRight) }
      waitUntil(timeoutMillis = TIMEOUT) { target.moveCalls.size == 1 }
      assertEquals(listOf("initial", "updated"), observed)
      map.assert(expectValue(SemanticsProperties.StateDescription, "engaged"))
    }
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
      options = MapGestures(from = MapGestures.None) { keys { rotaryZoom { enabled = true } } },
      rotaryNotchPixels = Float.POSITIVE_INFINITY,
    ) { _, _ ->
      onNodeWithTag(BEFORE_MAP_TAG).requestFocus()
      mapNode().performKeyInput { pressKey(Key.Tab) }
      onNodeWithTag(AFTER_MAP_TAG).assertIsFocused()
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
    val observed = mutableListOf<RotaryGestureEvent>()
    runFocusTest(
      options =
        MapGestures(from = MapGestures.None) {
          keys {
            rotaryZoom {
              enabled = true
              onEvent { observed += it }
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
      assertEquals(1, observed.map { it.gestureId }.distinct().size)
      map.assert(expectValue(SemanticsProperties.StateDescription, "not engaged"))
      mainClock.advanceTimeBy(250)
      waitForIdle()
      assertEquals(1, target.endedCount)
      map.performRotaryScrollInput { rotateToScrollVertically(24f) }
      assertEquals(2, observed.map { it.gestureId }.distinct().size)
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
    options: MapGestures = MapGestures.Standard,
    rotaryNotchPixels: Float = 0f,
    optionsProvider: () -> MapGestures = { options },
    body: ComposeUiTest.(RecordingGestureTarget, List<Key>) -> Unit,
  ) = runPlainComposeUiTest {
    val target = RecordingGestureTarget()
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
    options: MapGestures = MapGestures.Standard,
    parentOnClick: (() -> Unit)? = null,
    parentOnLongClick: (() -> Unit)? = null,
    parentModifier: Modifier = Modifier,
    optionsProvider: () -> MapGestures = { options },
    body: ComposeUiTest.(RecordingGestureTarget) -> Unit,
  ) = runPlainComposeUiTest {
    val target = RecordingGestureTarget()
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
    val SCROLL_HOLD_MILLIS = MapGestures.Standard.scrollIdleDuration.inWholeMilliseconds
  }
}

@Composable
private fun GestureHost(
  target: RecordingGestureTarget,
  options: MapGestures,
  rotaryNotchPixels: Float = 0f,
) {
  val density = LocalDensity.current
  val focusRequester = remember { FocusRequester() }
  val focus = remember { MapInputFocus {} }
  val environment = remember {
    MapInputEnvironment(
      contentDescription = "map",
      engaged = "engaged",
      notEngaged = "not engaged",
      indication = null,
    )
  }
  val inputScope = rememberCoroutineScope()
  val continuation = remember(inputScope) { GestureContinuation(inputScope) }
  Box(
    Modifier.fillMaxSize()
      .testTag(RECOGNITION_MAP_TAG)
      .mapInput(
        target,
        target,
        options,
        density,
        focusRequester,
        focus,
        environment,
        continuation,
        rotaryNotchPixels,
      )
  )
}

/**
 * Records every [GestureTarget] and [MapInteractionTarget] call so recognition tests can assert
 * without a map.
 */
private class RecordingGestureTarget : GestureTarget, MapInteractionTarget {
  override var capabilities = setOf(TapFamily.Tap, TapFamily.LongPress)
  val deliveredTapFamilies = mutableListOf<TapFamily>()

  override fun capture(family: TapFamily): MapClickPath =
    MapClickPath({ true }) {
      deliveredTapFamilies += family
      when (family) {
        TapFamily.Tap -> clicks++
        TapFamily.LongPress -> longClicks++
        else -> Unit
      }
      ClickResult.Pass
    }

  var clicks = 0
  var longClicks = 0
  var startedCount = 0
  var endedCount = 0
  val moveCalls = mutableListOf<Offset>()
  val scaleCalls = mutableListOf<ScaleCall>()
  val rotateCalls = mutableListOf<RotateCall>()
  val fitCalls = mutableListOf<Pair<BoxZoomFit, Duration>>()
  var project: (DpOffset) -> Position? = { null }

  override fun positionFromScreenLocation(offset: DpOffset): Position? = project(offset)

  override suspend fun fitBoundsAwaitingTransition(
    fit: BoxZoomFit,
    duration: Duration,
    gestureToken: GestureToken,
  ) {
    assertTrue(gestureToken.acceptsCommands)
    fitCalls += fit to duration
  }

  private var nextToken = 1L
  private var activeToken: GestureToken? = null
  private var camera = CameraPosition()

  override fun cancelTransitions() = Unit

  override fun getCameraPosition(): CameraPosition = camera

  override fun onGestureStarted(): GestureToken {
    val previous = activeToken
    previous?.job?.cancel()
    previous?.let(::cancelGesture)
    startedCount += 1
    return GestureToken(nextToken++).also { activeToken = it }
  }

  override fun onGestureEnded(token: GestureToken) {
    if (token.completion.isCompleted) return
    endedCount += 1
    token.complete()
    if (activeToken === token) activeToken = null
  }

  override fun moveBy(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: GestureToken?,
  ) {
    moveCalls += Offset(deltaX.toFloat(), deltaY.toFloat())
  }

  override fun scaleBy(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: GestureToken?,
  ) {
    scaleCalls += ScaleCall(scale, anchor)
  }

  override fun rotateAndPitchBy(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    anchor: DpOffset?,
    gestureToken: GestureToken?,
  ) {
    rotateCalls += RotateCall(bearingDelta, pitchDelta)
  }

  override suspend fun moveByAwaitingTransition(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: GestureToken,
  ) {
    moveBy(deltaX, deltaY, duration, gestureToken)
  }

  override suspend fun scaleByAwaitingTransition(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: GestureToken,
  ) {
    scaleBy(scale, anchor, duration, gestureToken)
  }

  override suspend fun rotateAndPitchByAwaitingTransition(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    gestureToken: GestureToken,
    anchor: DpOffset?,
  ) {
    rotateAndPitchBy(
      bearingDelta,
      pitchDelta,
      duration,
      anchor = anchor,
      gestureToken = gestureToken,
    )
  }

  data class ScaleCall(val scale: Double, val anchor: DpOffset?)

  data class RotateCall(val bearingDelta: Double, val pitchDelta: Double)
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
