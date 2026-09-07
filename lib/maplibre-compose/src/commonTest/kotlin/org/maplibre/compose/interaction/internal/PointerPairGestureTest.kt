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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.interaction.GestureAnchor
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.ModifierMatch
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
          camera {
            pan { onStart { starts++ } }
            zoom { onStart { starts++ } }
            rotate { onStart { starts++ } }
            tilt { onStart { starts++ } }
          }
          bindings {
            transform {
              pan {
                startSlop = 0.dp
              }
              zoom {
                startSpanSlop = 0.dp
              }
              rotate {
                startAngle = 0.0
              }
              tilt {
                startSlop = 0.dp
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
    val input =
      PairInput(
        MapInteractions(MapInteractions.None) {
          bindings {
            transform {
              zoom {
                enabled = true
                startSpanSlop = 20.dp
              }
            }
          }
        }
      )
    input.move(0, Offset(-100f, 0f), Offset(100f, 0f))
    val scale = input.target.scaleCalls.single().scale
    assertTrue(scale > 1.0 && scale < 200.0 / 160.0, "first delta must exclude span slop")
    assertNull(input.pair.end()?.scale, "equal timestamps fabricated zoom momentum")
  }

  @Test
  fun backwards_time_rebases_motion_without_a_camera_jump() {
    val input =
      PairInput(
        MapInteractions(MapInteractions.None) {
          camera { pan { momentum { enabled = false } } }
          bindings {
            transform {
              pan {
                enabled = true
                startSlop = 10.dp
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
              }
            }
          }
        }
      )
    input.move(20, Offset(0f, -80f), Offset(0f, 80f))
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
  fun zoom_priority_suppresses_simultaneous_and_previously_started_rotation() {
    fun configuration() =
      MapInteractions(MapInteractions.None) {
        bindings {
          transform {
            zoom { enabled = true }
            rotate {
              enabled = true
              allowDuringZoom = false
            }
          }
        }
      }
    val simultaneous = PairInput(configuration())
    simultaneous.move(20, Offset(-100f, -50f), Offset(100f, 50f))
    assertTrue(simultaneous.target.rotateCalls.isEmpty())
    assertEquals(1, simultaneous.target.scaleCalls.size)
    simultaneous.pair.end()
    val successive = PairInput(configuration())
    successive.move(20, Offset(0f, -80f), Offset(0f, 80f))
    assertEquals(1, successive.target.rotateCalls.size)
    val scalesBefore = successive.target.scaleCalls.size
    successive.move(40, Offset(0f, -140f), Offset(0f, 140f))
    successive.move(60, Offset(20f, -160f), Offset(-20f, 160f))
    successive.pair.end()
    assertEquals(1, successive.target.rotateCalls.size)
    assertTrue(successive.target.scaleCalls.size > scalesBefore)
  }

  @Test
  fun mouse_only_single_drag_leaves_touch_pair_pan_enabled() {
    val input =
      PairInput(
        MapInteractions {
          bindings { drag { pointerTypes = setOf(PointerType.Mouse) } }
        }
      )
    input.move(20, Offset(-50f, 0f), Offset(110f, 0f))
    input.move(40, Offset(-40f, 0f), Offset(120f, 0f))
    assertEquals(2, input.target.moveCalls.size)
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
  fun vertical_drift_during_rotation_does_not_start_tilt() {
    val input = PairInput(MapInteractions.Standard)
    fun move(at: Long, degrees: Double, vertical: Float) {
      val angle = degrees * PI / 180.0
      val radius = Offset(80f * cos(angle).toFloat(), 80f * sin(angle).toFloat())
      val center = Offset(0f, vertical)
      input.move(at, center - radius, center + radius)
    }
    move(20, 16.0, 0f)
    assertTrue(input.target.rotateCalls.any { it.bearingDelta != 0.0 })
    val rotations = input.target.rotateCalls.size
    move(40, 18.0, 24f)
    move(60, 20.0, 32f)
    assertTrue(input.target.rotateCalls.size > rotations, "rotation stopped during vertical drift")
    assertTrue(input.target.rotateCalls.all { it.pitchDelta == 0.0 }, "rotation became tilt")
  }

  @Test
  fun vertical_drift_during_pinch_does_not_start_tilt() {
    val input = PairInput(MapInteractions.Standard)
    input.move(20, Offset(-100f, 0f), Offset(100f, 0f))
    assertTrue(input.target.scaleCalls.isNotEmpty())
    val scales = input.target.scaleCalls.size
    input.move(40, Offset(-120f, 24f), Offset(120f, 24f))
    input.move(60, Offset(-140f, 32f), Offset(140f, 32f))
    assertTrue(input.target.scaleCalls.size > scales, "pinch stopped during vertical drift")
    assertTrue(input.target.rotateCalls.isEmpty(), "horizontal pinch became tilt")
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
    val options = initial

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
