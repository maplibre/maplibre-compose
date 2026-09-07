package org.maplibre.compose.interaction.internal

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTrackpadInput
import androidx.compose.ui.unit.dp
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.interaction.CameraInputStart
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.compose.map.GestureTestFixture
import org.maplibre.compose.map.RecordingGestureTarget
import org.maplibre.compose.mlnffi.runPlainComposeUiTest

@OptIn(ExperimentalAtomicApi::class, ExperimentalTestApi::class)
class TransformInputTest {
  private val fixture = GestureTestFixture()

  @AfterTest fun closeMap() = fixture.close()

  @Test
  fun trackpad_pan_and_scale_work_independently_of_scroll_bindings() {
    assumeTrackpadEventInjectionSupported()
    fixture.runRecognitionTest(
      options = MapInteractions { bindings { scroll { enabled = false } } }
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
      assertEquals(1, target.moveCalls.size)
      assertEquals(1.5, target.scaleCalls.single().scale, 1e-6)
      assertEquals(0, target.clicks)
    }
  }

  @Test
  fun a_structural_restart_suppresses_trackpad_changes_until_the_old_component_ends() {
    assumeTrackpadEventInjectionSupported()
    var options by mutableStateOf(MapInteractions.Standard)
    fixture.runRecognitionTest(optionsProvider = { options }) { target ->
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
  fun a_second_touch_interrupts_camera_motion_before_the_pair_crosses_slop() =
    fixture.runRecognitionTest(
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
  fun pair_pan_keeps_its_camera_session_until_the_last_contact_lifts() {
    fixture.runRecognitionTest(
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
    fixture.runRecognitionTest(
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
    fixture.runRecognitionTest(
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
          camera {
            zoom { momentum { enabled = false } }
            rotate { momentum { enabled = false } }
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
    fixture.runRecognitionTest(
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
    fixture.runRecognitionTest { target ->
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
    fixture.runRecognitionTest { target ->
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
    fixture.runRecognitionTest(
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
    fixture.runRecognitionTest(
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
    fixture.runRecognitionTest(
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
    fixture.runRecognitionTest(
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
  fun pair_to_single_pan_restarts_the_semantic_component_under_the_retained_session() {
    val starts = mutableListOf<CameraInputStart>()
    fixture.runRecognitionTest(
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
}
