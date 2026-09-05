package org.maplibre.compose.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import org.maplibre.compose.camera.CameraPosition

class PointerPairGestureTest {
  @Test
  fun zero_slop_still_requires_actual_motion_in_that_component() {
    var starts = 0
    val input =
      PairInput(
        MapGestures {
          dragPan {
            startSlop = 0.dp
            onStart { starts++ }
          }
          pinchZoom {
            startSpanSlop = 0.dp
            onStart { starts++ }
          }
          twoFingerRotate {
            startAngle = 0.0
            onStart { starts++ }
          }
          twoFingerTilt {
            startSlop = 0.dp
            onStart { starts++ }
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
        MapGestures(MapGestures.None) {
          pinchZoom {
            enabled = true
            startSpanSlop = 20.dp
            onStart { events += it }
            onDelta { events += it }
            onEnd { events += it }
          }
        }
      )
    input.move(0, Offset(-100f, 0f), Offset(100f, 0f))
    input.pair.end(75)
    assertEquals(75, events.last().uptimeMillis)
    assertEquals(3, events.size)
    assertEquals(200.0 / 170, (events[1] as PinchEvent.Delta).scaleFactor, 1e-9)
    assertEquals(0.0, (events.last() as PinchEvent.End).zoomVelocity)
  }

  @Test
  fun backwards_time_rebases_motion_without_a_camera_jump() {
    val input =
      PairInput(
        MapGestures(MapGestures.None) {
          dragPan {
            enabled = true
            startSlop = 10.dp
            continuation = null
          }
        }
      )
    input.move(20, Offset(-50f, 0f), Offset(110f, 0f))
    assertEquals(listOf(Offset(20f, 0f)), input.target.moves)
    input.move(10, Offset(-20f, 0f), Offset(140f, 0f))
    assertEquals(1, input.target.moves.size)
    input.move(30, Offset(-15f, 0f), Offset(145f, 0f))
    assertEquals(Offset(5f, 0f), input.target.moves.last())
  }

  @Test
  fun rotation_uses_selected_angle_slop_and_response_gain() {
    val events = mutableListOf<RotateEvent>()
    val input =
      PairInput(
        MapGestures(MapGestures.None) {
          twoFingerRotate {
            enabled = true
            startAngle = 45.0
            rotationScale = 2.0
            anchor = GestureAnchor.CameraCenter
            onStart { events += it }
            onDelta { events += it }
          }
        }
      )
    input.move(20, Offset(0f, -80f), Offset(0f, 80f))
    assertEquals(45.0, (events[1] as RotateEvent.Delta).degrees, 1e-9)
    assertEquals(-90.0, input.target.bearings.single(), 1e-9)
    assertEquals(null, input.target.rotationAnchors.single())
  }

  @Test
  fun pinch_and_rotation_keep_independent_anchors_and_gains() {
    val input =
      PairInput(
        MapGestures(MapGestures.None) {
          pinchZoom {
            enabled = true
            zoomScale = 2.0
            anchor = GestureAnchor.CameraCenter
          }
          twoFingerRotate {
            enabled = true
            anchor = GestureAnchor.Input
          }
        }
      )
    input.move(20, Offset(-100f, 0f), Offset(100f, 0f))
    assertEquals(GestureMath.pinchScale(200.0 / 163.5).pow(2), input.target.scales.single(), 1e-9)
    assertEquals(null, input.target.scaleAnchors.single())
    input.move(40, Offset(10f, -100f), Offset(10f, 100f))
    assertEquals(DpOffset(10.dp, 0.dp), input.target.rotationAnchors.single())
  }

  @Test
  fun all_reported_contact_types_must_match_the_pair_filter() {
    val input =
      PairInput(
        MapGestures(MapGestures.None) {
          pinchZoom {
            enabled = true
            filter = PointerFilter(pointerTypes = setOf(PointerType.Touch))
          }
        },
        secondType = PointerType.Stylus,
      )
    assertFalse(input.pair.hasDemand)
    input.move(20, Offset(-120f, 0f), Offset(120f, 0f))
    assertTrue(input.target.scales.isEmpty())
  }

  @Test
  fun a_cancel_callback_failure_still_cleans_up_other_started_components_once() {
    val failure = IllegalStateException("observer")
    var pinchCancels = 0
    var panCancels = 0
    val input =
      PairInput(
        MapGestures(MapGestures.None) {
          dragPan {
            enabled = true
            onCancel {
              panCancels++
              throw failure
            }
          }
          pinchZoom {
            enabled = true
            onCancel { pinchCancels++ }
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
        MapGestures(MapGestures.None) {
          dragPan {
            enabled = true
            onStart { starts += it.gestureId }
            onDelta { first += it.gestureId }
          }
        }
      )
    input.move(20, Offset(-50f, 0f), Offset(110f, 0f))
    input.options = MapGestures(input.options) { dragPan { onDelta { second += it.gestureId } } }
    input.move(40, Offset(-40f, 0f), Offset(120f, 0f))
    assertEquals(starts, first)
    assertEquals(starts, second)
  }

  private class PairInput(
    var options: MapGestures,
    private val secondType: PointerType = PointerType.Touch,
  ) {
    val target = PairTarget()
    private var time = 0L
    private var positions = listOf(Offset(-80f, 0f), Offset(80f, 0f))
    private val token = GestureToken(1)
    val pair: PointerPairGesture

    init {
      val event = event(0, positions)
      pair =
        PointerPairGesture(
          target,
          options,
          { options },
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

  private class PairTarget : GestureTarget {
    val moves = mutableListOf<Offset>()
    val scales = mutableListOf<Double>()
    val bearings = mutableListOf<Double>()
    val scaleAnchors = mutableListOf<DpOffset?>()
    val rotationAnchors = mutableListOf<DpOffset?>()

    override fun cancelTransitions() = Unit

    override fun getCameraPosition(): CameraPosition = CameraPosition()

    override fun onGestureStarted(): GestureToken = error("the contact group supplies its token")

    override fun onGestureEnded(token: GestureToken) = Unit

    override fun moveBy(
      deltaX: Double,
      deltaY: Double,
      duration: Duration,
      gestureToken: GestureToken?,
    ) {
      moves += Offset(deltaX.toFloat(), deltaY.toFloat())
    }

    override fun scaleBy(
      scale: Double,
      anchor: DpOffset?,
      duration: Duration,
      gestureToken: GestureToken?,
    ) {
      scales += scale
      scaleAnchors += anchor
    }

    override fun rotateAndPitchBy(
      bearingDelta: Double,
      pitchDelta: Double,
      duration: Duration,
      anchor: DpOffset?,
      gestureToken: GestureToken?,
    ) {
      bearings += bearingDelta
      rotationAnchors += anchor
    }

    override suspend fun moveByAwaitingTransition(
      deltaX: Double,
      deltaY: Double,
      duration: Duration,
      gestureToken: GestureToken,
    ) = error("pair input does not ease")

    override suspend fun scaleByAwaitingTransition(
      scale: Double,
      anchor: DpOffset?,
      duration: Duration,
      gestureToken: GestureToken,
    ) = error("pair input does not ease")

    override suspend fun rotateAndPitchByAwaitingTransition(
      bearingDelta: Double,
      pitchDelta: Double,
      duration: Duration,
      gestureToken: GestureToken,
      anchor: DpOffset?,
    ) = error("pair input does not ease")
  }
}
