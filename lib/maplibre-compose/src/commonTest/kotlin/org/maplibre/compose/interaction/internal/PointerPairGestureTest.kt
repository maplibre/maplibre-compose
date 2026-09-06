package org.maplibre.compose.interaction.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.interaction.GestureAnchor
import org.maplibre.compose.interaction.GestureCancellationReason
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.ModifierMatch
import org.maplibre.compose.interaction.PinchEvent
import org.maplibre.compose.interaction.RotateEvent
import org.maplibre.compose.map.GestureTestFixture

class PointerPairGestureTest {
  private val map = GestureTestFixture()

  @AfterTest fun closeMap() = map.close()

  @Test
  fun zero_slop_still_requires_actual_motion_in_that_component() {
    var starts = 0
    val input =
      PairInput(
        MapInteractions {
          bindings {
            transform {
              pan {
                startSlop = 0.dp
                onStart { starts++ }
              }
              zoom {
                startSpanSlop = 0.dp
                onStart { starts++ }
              }
              rotate {
                startAngle = 0.0
                onStart { starts++ }
              }
              tilt {
                startSlop = 0.dp
                onStart { starts++ }
              }
            }
          }
        }
      )
    input.move(0, Offset(-80f, 0f), Offset(80f, 0f))
    assertEquals(0, starts)
  }

  @Test
  fun equal_time_samples_recognize_slop_without_fabricating_release_velocity() {
    val events = mutableListOf<PinchEvent>()
    val input =
      PairInput(
        MapInteractions(MapInteractions.None) {
          bindings {
            transform {
              zoom {
                enabled = true
                startSpanSlop = 20.dp
                onStart { events += it }
                onDelta { events += it }
                onEnd { events += it }
              }
            }
          }
        }
      )
    input.move(0, Offset(-100f, 0f), Offset(100f, 0f))
    input.pair.end(75)
    assertEquals(75, events.last().uptimeMillis)
    assertEquals(3, events.size)
    val scale = (events[1] as PinchEvent.Delta).scaleFactor
    assertTrue(scale > 1.0 && scale < 200.0 / 160.0, "first delta must exclude span slop")
    assertEquals(0.0, (events.last() as PinchEvent.End).zoomVelocity)
  }

  @Test
  fun backwards_time_rebases_motion_without_a_camera_jump() {
    val input =
      PairInput(
        MapInteractions(MapInteractions.None) {
          bindings {
            transform {
              pan {
                enabled = true
                startSlop = 10.dp
                momentum { enabled = false }
              }
            }
          }
        }
      )
    input.move(20, Offset(-50f, 0f), Offset(110f, 0f))
    assertEquals(listOf(Offset(20f, 0f)), input.target.moveCalls)
    input.move(10, Offset(-20f, 0f), Offset(140f, 0f))
    assertEquals(1, input.target.moveCalls.size)
    input.move(30, Offset(-15f, 0f), Offset(145f, 0f))
    assertEquals(Offset(5f, 0f), input.target.moveCalls.last())
  }

  @Test
  fun rotation_uses_selected_angle_slop_and_response_gain() {
    val events = mutableListOf<RotateEvent>()
    val input =
      PairInput(
        MapInteractions(MapInteractions.None) {
          bindings {
            transform {
              rotate {
                enabled = true
                startAngle = 45.0
                rotationScale = 2.0
                anchor = GestureAnchor.CameraCenter
                onStart { events += it }
                onDelta { events += it }
              }
            }
          }
        }
      )
    input.move(20, Offset(0f, -80f), Offset(0f, 80f))
    assertEquals(45.0, (events[1] as RotateEvent.Delta).degrees, 1e-9)
    assertEquals(-90.0, input.target.rotateCalls.single().bearingDelta, 1e-9)
    assertEquals(null, input.target.rotateCalls.single().anchor)
  }

  @Test
  fun pinch_and_rotation_keep_independent_anchors_and_gains() {
    fun options(gain: Double) =
      MapInteractions(MapInteractions.None) {
        bindings {
          transform {
            zoom {
              enabled = true
              zoomScale = gain
              anchor = GestureAnchor.CameraCenter
            }
            rotate {
              enabled = true
              anchor = GestureAnchor.Input
            }
          }
        }
      }
    val baseline = PairInput(options(1.0))
    baseline.move(20, Offset(-100f, 0f), Offset(100f, 0f))
    val scale = baseline.target.scaleCalls.single().scale
    assertTrue(scale > 1.0)
    val input = PairInput(options(2.0))
    input.move(20, Offset(-100f, 0f), Offset(100f, 0f))
    assertEquals(scale * scale, input.target.scaleCalls.last().scale, 1e-9)
    assertEquals(null, input.target.scaleCalls.last().anchor)
    input.move(40, Offset(10f, -100f), Offset(10f, 100f))
    assertEquals(DpOffset(10.dp, 0.dp), input.target.rotateCalls.single().anchor)
  }

  @Test
  fun contact_types_and_modifiers_must_match_the_pair_filter() {
    for (filter in
      listOf(
        PointerPattern(pointerTypes = setOf(PointerType.Touch)),
        PointerPattern(modifiers = ModifierMatch.Containing(KeyModifier.Ctrl)),
      )) {
      val input =
        PairInput(
          MapInteractions(MapInteractions.None) {
            bindings {
              transform {
                zoom {
                  enabled = true
                  pointerTypes = filter.pointerTypes
                  modifiers = filter.modifiers
                }
              }
            }
          },
          secondType = PointerType.Stylus,
        )
      assertFalse(input.pair.hasDemand)
      input.move(20, Offset(-120f, 0f), Offset(120f, 0f))
      assertTrue(input.target.scaleCalls.isEmpty())
    }
  }

  @Test
  fun a_cancel_callback_failure_still_cleans_up_other_started_components_once() {
    val failure = IllegalStateException("observer")
    var pinchCancels = 0
    var panCancels = 0
    val input =
      PairInput(
        MapInteractions(MapInteractions.None) {
          bindings {
            transform {
              pan {
                enabled = true
                onCancel {
                  panCancels++
                  throw failure
                }
              }
              zoom {
                enabled = true
                onCancel { pinchCancels++ }
              }
            }
          }
        }
      )
    input.move(20, Offset(-100f, 30f), Offset(100f, 30f))
    assertEquals(
      failure,
      assertFailsWith<IllegalStateException> {
        input.pair.cancel(GestureCancellationReason.InputConsumed)
      },
    )
    input.pair.cancel(GestureCancellationReason.InputCancelled)
    assertEquals(1, panCancels)
    assertEquals(1, pinchCancels)
  }

  @Test
  fun pair_callbacks_read_updated_handlers_without_restarting_the_component() {
    val starts = mutableListOf<Long>()
    val first = mutableListOf<Long>()
    val second = mutableListOf<Long>()
    val input =
      PairInput(
        MapInteractions(MapInteractions.None) {
          bindings {
            transform {
              pan {
                enabled = true
                onStart { starts += it.gestureId }
                onDelta { first += it.gestureId }
              }
            }
          }
        }
      )
    input.move(20, Offset(-50f, 0f), Offset(110f, 0f))
    input.options =
      MapInteractions(input.options) {
        bindings { transform { pan { onDelta { second += it.gestureId } } } }
      }
    input.move(40, Offset(-40f, 0f), Offset(120f, 0f))
    assertEquals(listOf(starts.single()), first)
    assertEquals(starts, second)
  }

  @Test
  fun zoom_priority_suppresses_simultaneous_rotation_and_cancels_a_previous_rotation_once() {
    val rotations = mutableListOf<RotateEvent>()
    fun configuration() =
      MapInteractions(MapInteractions.None) {
        bindings {
          transform {
            zoom { enabled = true }
            rotate {
              enabled = true
              allowDuringZoom = false
              onStart { rotations += it }
              onCancel { rotations += it }
              onEnd { rotations += it }
            }
          }
        }
      }
    val simultaneous = PairInput(configuration())
    simultaneous.move(20, Offset(-100f, -50f), Offset(100f, 50f))
    assertTrue(rotations.isEmpty())
    assertEquals(1, simultaneous.target.scaleCalls.size)
    simultaneous.pair.end()
    val successive = PairInput(configuration())
    successive.move(20, Offset(0f, -80f), Offset(0f, 80f))
    assertTrue(rotations.single() is RotateEvent.Start)
    successive.move(40, Offset(0f, -140f), Offset(0f, 140f))
    successive.move(60, Offset(20f, -160f), Offset(-20f, 160f))
    successive.pair.end()
    assertEquals(2, rotations.size)
    assertTrue(rotations.last() is RotateEvent.Cancel)
  }

  @Test
  fun mouse_only_single_drag_leaves_touch_pair_pan_and_late_observers_do_not_join() {
    var lateDeltas = 0
    val input =
      PairInput(
        MapInteractions {
          bindings { drag { pointerTypes = setOf(PointerType.Mouse) } }
        }
      )
    input.move(20, Offset(-50f, 0f), Offset(110f, 0f))
    input.options =
      MapInteractions(input.options) {
        bindings { transform { pan { onDelta { lateDeltas++ } } } }
      }
    input.move(40, Offset(-40f, 0f), Offset(120f, 0f))
    assertEquals(2, input.target.moveCalls.size)
    assertEquals(0, lateDeltas)
  }

  @Test
  fun removed_and_readded_observer_cannot_join_the_existing_pan_between_input_events() {
    val seen = mutableListOf<String>()
    val input =
      PairInput(
        MapInteractions {
          bindings { transform { pan { onDelta { seen += "original" } } } }
        }
      )
    input.move(20, Offset(-50f, 0f), Offset(110f, 0f))
    input.options =
      MapInteractions(input.options) {
        bindings { transform { pan { onDelta(null) } } }
      }
    input.options =
      MapInteractions(input.options) {
        bindings { transform { pan { onDelta { seen += "readded" } } } }
      }
    input.move(40, Offset(-40f, 0f), Offset(120f, 0f))
    input.pair.end()
    assertEquals(listOf("original"), seen)
    assertEquals(2, input.target.moveCalls.size)
    val next = PairInput(input.options)
    next.move(20, Offset(-50f, 0f), Offset(110f, 0f))
    assertEquals(listOf("original", "readded"), seen)
  }

  @Test
  fun pinch_momentum_does_not_depend_on_which_finger_moves() {
    val options =
      MapInteractions(MapInteractions.None) {
        bindings { transform { zoom { enabled = true } } }
      }
    fun release(movingFirst: Boolean): GestureMath.ScaleVelocity {
      val input = PairInput(options)
      repeat(6) { index ->
        val distance = (index + 1) * 24f
        input.move(
          (index + 1) * 16L,
          Offset(-80f - if (movingFirst) distance else 0f, 0f),
          Offset(80f + if (movingFirst) 0f else distance, 0f),
        )
      }
      val motion = assertNotNull(input.pair.end())
      assertNull(motion.pan)
      return assertNotNull(motion.scale)
    }
    val first = release(true)
    val second = release(false)
    assertTrue(first.zoomDelta > 0.0)
    assertEquals(first.zoomDelta, second.zoomDelta, 1e-6)
    assertEquals(first.duration, second.duration)
  }

  @Test
  fun a_small_sideways_shift_at_the_end_of_a_pinch_does_not_start_rotation() {
    val input = PairInput(MapInteractions.Standard)
    for ((index, span) in listOf(150f, 120f, 90f, 60f).withIndex()) {
      input.move((index + 1) * 16L, Offset(-span, 0f), Offset(span, 0f))
    }
    val angle = 20.0 * PI / 180.0
    val radius = Offset(25f * cos(angle).toFloat(), 25f * sin(angle).toFloat())
    input.move(80, -radius, radius)
    assertTrue(input.target.scaleCalls.isNotEmpty())
    assertTrue(input.target.rotateCalls.isEmpty(), "closing the pinch started rotation")
    val momentum = assertNotNull(input.pair.end())
    assertNotNull(momentum.scale)
    assertNull(momentum.rotation)
  }

  @Test
  fun newly_recognized_rotation_does_not_inherit_pre_recognition_velocity() {
    val input =
      PairInput(
        MapInteractions(MapInteractions.None) {
          bindings { transform { rotate { enabled = true } } }
        }
      )
    for ((index, degrees) in listOf(1.0, 2.0, 20.0).withIndex()) {
      val angle = degrees * PI / 180.0
      val radius = Offset(80f * cos(angle).toFloat(), 80f * sin(angle).toFloat())
      input.move((index + 1) * 16L, -radius, radius)
    }
    assertTrue(input.target.rotateCalls.isNotEmpty(), "rotation did not recognize")
    assertNull(input.pair.end()?.rotation, "recognition alone fabricated a rotational flick")
  }

  @Test
  fun rotation_momentum_does_not_depend_on_position_on_the_screen() {
    val options =
      MapInteractions(MapInteractions.None) {
        bindings { transform { rotate { enabled = true } } }
      }
    fun release(center: Offset): GestureMath.RotationVelocity {
      val input = PairInput(options, center = center)
      repeat(6) { index ->
        val angle = (index + 1) * 16.0 * PI / 180.0
        val radius = Offset(80f * cos(angle).toFloat(), 80f * sin(angle).toFloat())
        input.move((index + 1) * 16L, center - radius, center + radius)
      }
      val motion = assertNotNull(input.pair.end())
      assertNull(motion.pan)
      return assertNotNull(motion.rotation)
    }
    val origin = release(Offset.Zero)
    val translated = release(Offset(300f, 600f))
    assertEquals(origin.bearingDelta, translated.bearingDelta, 1e-3)
    assertEquals(origin.duration, translated.duration)
  }

  private inner class PairInput(
    initial: MapInteractions,
    private val secondType: PointerType = PointerType.Touch,
    center: Offset = Offset.Zero,
  ) {
    val subscriptions = InteractionSubscriptions(initial)
    var options = initial
      set(value) {
        field = value
        subscriptions.update(value)
      }

    val target = map.target
    private var time = 0L
    private var positions = listOf(center + Offset(-80f, 0f), center + Offset(80f, 0f))
    private val token = run {
      map.state.gestureAuthority.updateConfiguration(options.camera)
      target.onGestureStarted()
    }
    val pair: PointerPairGesture

    init {
      val event = event(0, positions)
      pair =
        PointerPairGesture(
          target,
          options,
          { options },
          subscriptions,
          GestureIds(),
          Density(1f),
          event,
          event.changes[0],
          event.changes[1],
          { token },
          {},
          { token.acceptsCommands },
        )
    }

    fun move(at: Long, first: Offset, second: Offset) {
      val next = listOf(first, second)
      val event = event(at, next)
      pair.move(event, event.changes[0], event.changes[1])
      time = at
      positions = next
    }

    private fun event(at: Long, next: List<Offset>): PointerEvent =
      PointerEvent(
        next.mapIndexed { index, position ->
          PointerInputChange(
            id = PointerId(index.toLong()),
            uptimeMillis = at,
            position = position,
            pressed = true,
            previousUptimeMillis = time,
            previousPosition = positions[index],
            previousPressed = true,
            isInitiallyConsumed = false,
            type = if (index == 0) PointerType.Touch else secondType,
          )
        }
      )
  }
}
