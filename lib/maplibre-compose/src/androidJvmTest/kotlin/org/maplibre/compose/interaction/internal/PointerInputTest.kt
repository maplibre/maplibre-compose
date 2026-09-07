package org.maplibre.compose.interaction.internal

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.GestureTestFixture
import org.maplibre.compose.map.RecordingGestureTarget
import org.maplibre.compose.style.scaledBy
import org.maplibre.compose.style.systemAnimatorDurationScale
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalAtomicApi::class, ExperimentalTestApi::class)
class PointerInputTest {
  private val fixture = GestureTestFixture()

  @AfterTest fun closeMap() = fixture.close()

  @Test
  fun contacts_rejected_before_a_viewport_wait_for_release() =
    fixture.runRecognitionTest { target ->
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
  fun a_closed_map_ignores_input_while_still_composed() = fixture.runRecognitionTest { target ->
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
  fun shift_drag_draws_a_selection_then_fits_under_the_same_session() =
    fixture.runRecognitionTest { target ->
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
    fixture.runRecognitionTest(optionsProvider = { configuration }) { target ->
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
  fun camera_start_takeover_survives_final_pass_consumption() {
    var recorded: RecordingGestureTarget? = null
    var newer: CameraInputToken? = null
    fixture.runRecognitionTest(
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
  fun a_structural_change_cancels_the_drag_and_waits_for_existing_contacts_to_lift() {
    var configuration by mutableStateOf(MapInteractions.Standard)
    fixture.runRecognitionTest(optionsProvider = { configuration }) { target ->
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
    fixture.runRecognitionTest(
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
  fun a_consumed_drag_move_cancels_without_fling_and_waits_for_all_contacts_to_lift() {
    var intercept = false
    fixture.runRecognitionTest(
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
  fun the_arenas_own_consumption_does_not_cancel_its_drag_in_final() =
    fixture.runRecognitionTest { target ->
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
  fun secondary_mouse_drag_requests_rotate_and_tilt() = fixture.runRecognitionTest { target ->
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
  fun a_thin_box_ends_without_a_fit_or_click() = fixture.runRecognitionTest { target ->
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
  fun an_initial_consumed_press_cannot_click_drag_or_engage_the_map() =
    fixture.runRecognitionTest(
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
}
