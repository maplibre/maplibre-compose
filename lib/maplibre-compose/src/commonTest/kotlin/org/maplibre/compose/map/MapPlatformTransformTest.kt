package org.maplibre.compose.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.camera.CameraPosition

@OptIn(ExperimentalCoroutinesApi::class)
class MapPlatformTransformTest {
  @Test
  fun missing_scale_start_uses_supplied_scale_gain_anchor_and_mouse_metadata() = runTest {
    val events = mutableListOf<PinchEvent>()
    val fixture =
      Fixture(
        backgroundScope,
        MapGestures {
          pinchZoom {
            zoomScale = 0.5
            anchor = GestureAnchor.CameraCenter
            onStart { events += it }
            onDelta { events += it }
            onEnd { events += it }
          }
        },
      )
    try {
      assertTrue(fixture.input.onInput(PointerEventType.ScaleChange, sample(10), scaleFactor = 2.0))
      fixture.input.onInput(PointerEventType.ScaleEnd, sample(20))
      fixture.input.onInput(PointerEventType.ScaleEnd, sample(20))
      runCurrent()
      assertEquals(3, events.size)
      assertTrue(events[0] is PinchEvent.Start)
      assertEquals(2.0, (events[1] as PinchEvent.Delta).scaleFactor)
      assertTrue(events[2] is PinchEvent.End)
      assertEquals(setOf(PointerType.Mouse), events[0].pointerTypes)
      assertTrue(events[0].buttons.isEmpty())
      assertEquals(sqrt(2.0), fixture.target.scales.single().first, 1e-6)
      assertEquals(null, fixture.target.scales.single().second)
      assertEquals(1, fixture.target.ends)
    } finally {
      fixture.input.cancel()
    }
  }

  @Test
  fun pan_and_scale_overlap_in_one_session_and_end_without_added_momentum() = runTest {
    val pans = mutableListOf<DragEvent>()
    val pinches = mutableListOf<PinchEvent>()
    val fixture =
      Fixture(
        backgroundScope,
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
        },
      )
    try {
      fixture.input.onInput(PointerEventType.PanStart, sample(0))
      fixture.input.onInput(PointerEventType.ScaleStart, sample(0))
      fixture.input.onInput(
        PointerEventType.PanMove,
        sample(10),
        panDelta = DpOffset(20.dp, (-10).dp),
      )
      fixture.input.onInput(
        PointerEventType.PanMove,
        sample(20),
        panDelta = DpOffset(20.dp, (-10).dp),
      )
      fixture.input.onInput(PointerEventType.ScaleChange, sample(20), scaleFactor = 2.0)
      fixture.input.onInput(PointerEventType.PanEnd, sample(30))
      assertEquals(0, fixture.target.ends)
      fixture.input.onInput(PointerEventType.ScaleEnd, sample(30))
      runCurrent()
      assertEquals(1, fixture.target.starts)
      assertEquals(1, fixture.target.ends)
      assertEquals(2, fixture.target.moves.size)
      assertEquals(1, fixture.target.scales.size)
      assertTrue(pans.first().gestureId != pinches.first().gestureId)
      val velocity = (pans.last() as DragEvent.End).velocity
      assertEquals(2000.0, velocity.xDpPerSecond, 0.01)
      assertEquals(-1000.0, velocity.yDpPerSecond, 0.01)
    } finally {
      fixture.input.cancel()
    }
  }

  @Test
  fun invalid_deltas_do_not_start_and_backward_time_rebases_without_motion() = runTest {
    val fixture = Fixture(backgroundScope)
    try {
      for (value in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
        assertFalse(
          fixture.input.onInput(PointerEventType.ScaleChange, sample(0), scaleFactor = value)
        )
      }
      assertEquals(0, fixture.target.starts)
      fixture.input.onInput(PointerEventType.PanStart, sample(0))
      fixture.input.onInput(PointerEventType.PanMove, sample(10), panDelta = DpOffset(10.dp, 0.dp))
      fixture.input.onInput(PointerEventType.PanMove, sample(5), panDelta = DpOffset(500.dp, 0.dp))
      fixture.input.onInput(PointerEventType.PanMove, sample(15), panDelta = DpOffset(4.dp, 0.dp))
      fixture.input.onInput(PointerEventType.PanEnd, sample(20))
      assertEquals(listOf(Offset(10f, 0f), Offset(4f, 0f)), fixture.target.moves)
    } finally {
      fixture.input.cancel()
    }
  }

  @Test
  fun equal_time_deltas_all_apply_and_velocity_uses_coalesced_samples() = runTest {
    var end: DragEvent.End? = null
    val fixture = Fixture(backgroundScope, MapGestures { dragPan { onEnd { end = it } } })
    try {
      fixture.input.onInput(PointerEventType.PanStart, sample(0))
      for (time in listOf(10L, 10L, 10L)) fixture.input.onInput(
        PointerEventType.PanMove,
        sample(time),
        panDelta = DpOffset(10.dp, 0.dp),
      )
      fixture.input.onInput(PointerEventType.PanEnd, sample(25))
      assertEquals(3, fixture.target.moves.size)
      assertEquals(3000.0, checkNotNull(end).velocity.xDpPerSecond, 0.1)
    } finally {
      fixture.input.cancel()
    }
  }

  @Test
  fun consumption_cancels_once_and_suppresses_the_stream_until_its_end() = runTest {
    val cancellations = mutableListOf<GestureCancellationReason>()
    val fixture =
      Fixture(
        backgroundScope,
        MapGestures { pinchZoom { onCancel { cancellations += it.reason } } },
      )
    try {
      fixture.input.onInput(PointerEventType.ScaleStart, sample(0))
      assertFalse(
        fixture.input.onInput(
          PointerEventType.ScaleChange,
          sample(10),
          scaleFactor = 2.0,
          consumed = true,
        )
      )
      assertFalse(
        fixture.input.onInput(PointerEventType.ScaleChange, sample(20), scaleFactor = 2.0)
      )
      fixture.input.onInput(PointerEventType.ScaleEnd, sample(30))
      fixture.input.onInput(PointerEventType.ScaleStart, sample(40))
      fixture.input.onInput(PointerEventType.ScaleChange, sample(50), scaleFactor = 2.0)
      fixture.input.onInput(PointerEventType.ScaleEnd, sample(60))
      assertEquals(listOf(GestureCancellationReason.InputConsumed), cancellations)
      assertEquals(1, fixture.target.scales.size)
      assertEquals(2, fixture.target.starts)
    } finally {
      fixture.input.cancel()
    }
  }

  @Test
  fun observer_takeover_prevents_response_and_cancels_both_components() = runTest {
    val cancellations = mutableListOf<String>()
    lateinit var fixture: Fixture
    fixture =
      Fixture(
        backgroundScope,
        MapGestures {
          dragPan { onCancel { cancellations += "pan" } }
          pinchZoom {
            onDelta { fixture.target.onGestureStarted() }
            onCancel { cancellations += "scale" }
          }
        },
      )
    try {
      fixture.input.onInput(PointerEventType.PanStart, sample(0))
      fixture.input.onInput(PointerEventType.ScaleStart, sample(0))
      fixture.input.onInput(PointerEventType.ScaleChange, sample(10), scaleFactor = 2.0)
      runCurrent()
      assertEquals(setOf("pan", "scale"), cancellations.toSet())
      assertEquals(2, cancellations.size)
      assertTrue(fixture.target.scales.isEmpty())
      assertFalse(
        fixture.input.onInput(
          PointerEventType.PanMove,
          sample(20),
          panDelta = DpOffset(10.dp, 0.dp),
        )
      )
    } finally {
      fixture.input.cancel()
      fixture.target.close()
    }
  }

  @Test
  fun callback_replacement_keeps_the_component_without_restarting() = runTest {
    val calls = mutableListOf<String>()
    lateinit var fixture: Fixture
    fun configuration(name: String) = MapGestures {
      dragPan {
        onDelta { calls += name }
        onEnd { fixture.target.onGestureStarted() }
      }
    }
    fixture = Fixture(backgroundScope, configuration("old"))
    try {
      fixture.input.onInput(PointerEventType.PanStart, sample(0))
      fixture.options = configuration("new")
      fixture.input.onInput(
        PointerEventType.PanMove,
        sample(10),
        panDelta = DpOffset(10.dp, 0.dp),
      )
      fixture.input.onInput(PointerEventType.PanEnd, sample(20))
      assertEquals(listOf("new"), calls)
      assertEquals(1, fixture.target.moves.size)
      assertEquals(2, fixture.target.starts)
    } finally {
      fixture.input.cancel()
      fixture.target.close()
    }
  }

  @Test
  fun one_throwing_cancel_still_cleans_up_every_component_and_the_session() = runTest {
    var panCancels = 0
    val failure = IllegalStateException("scale cancel")
    val fixture =
      Fixture(
        backgroundScope,
        MapGestures {
          pinchZoom { onCancel { throw failure } }
          dragPan { onCancel { panCancels++ } }
        },
      )
    fixture.input.onInput(PointerEventType.ScaleStart, sample(0))
    fixture.input.onInput(PointerEventType.PanStart, sample(0))
    assertEquals(failure, assertFailsWith<IllegalStateException> { fixture.input.cancel() })
    fixture.input.cancel()
    assertEquals(1, panCancels)
    assertEquals(1, fixture.target.ends)
  }

  @Test
  fun structural_restart_does_not_resume_a_cancelled_buttonless_component() = runTest {
    val fixture = Fixture(backgroundScope)
    fixture.input.onInput(PointerEventType.ScaleStart, sample(0))
    fixture.input.cancel(GestureCancellationReason.ConfigurationChanged)
    val replacement =
      MapPlatformTransform(
        fixture.target,
        MapGestures.Standard,
        { MapGestures.Standard },
        GestureIds(),
        backgroundScope,
        fixture.routing,
        {},
      )
    try {
      assertFalse(replacement.onInput(PointerEventType.ScaleChange, sample(10), scaleFactor = 2.0))
      assertTrue(fixture.target.scales.isEmpty())
      replacement.onInput(PointerEventType.ScaleEnd, sample(20))
      assertTrue(replacement.onInput(PointerEventType.ScaleChange, sample(30), scaleFactor = 2.0))
      replacement.onInput(PointerEventType.ScaleEnd, sample(40))
      assertEquals(1, fixture.target.scales.size)
      assertEquals(2, fixture.target.starts)
    } finally {
      replacement.cancel()
      fixture.input.cancel()
    }
  }

  @Test
  fun modifier_changes_cancel_a_stream_without_restarting_until_end() = runTest {
    val cancellations = mutableListOf<GestureCancellationReason>()
    val fixture =
      Fixture(backgroundScope, MapGestures { dragPan { onCancel { cancellations += it.reason } } })
    try {
      fixture.input.onInput(PointerEventType.PanStart, sample(0))
      val shifted = sample(10).copy(modifierKeys = setOf(KeyModifier.Shift))
      assertFalse(
        fixture.input.onInput(PointerEventType.PanMove, shifted, panDelta = DpOffset(10.dp, 0.dp))
      )
      assertFalse(
        fixture.input.onInput(
          PointerEventType.PanMove,
          sample(20),
          panDelta = DpOffset(10.dp, 0.dp),
        )
      )
      fixture.input.onInput(PointerEventType.PanEnd, sample(30))
      assertEquals(listOf(GestureCancellationReason.BindingChanged), cancellations)
      assertTrue(fixture.target.moves.isEmpty())
      assertTrue(fixture.input.onInput(PointerEventType.PanStart, shifted.copy(uptimeMillis = 40)))
    } finally {
      fixture.input.cancel()
    }
  }

  @Test
  fun takeover_before_release_delivers_cancel_even_before_the_cancelled_job_runs() = runTest {
    val events = mutableListOf<PinchEvent>()
    val fixture =
      Fixture(
        backgroundScope,
        MapGestures {
          pinchZoom {
            onEnd { events += it }
            onCancel { events += it }
          }
        },
      )
    try {
      fixture.input.onInput(PointerEventType.ScaleStart, sample(0))
      fixture.target.onGestureStarted()
      fixture.input.onInput(PointerEventType.ScaleEnd, sample(10))
      assertEquals(1, events.size)
      assertEquals(
        GestureCancellationReason.CameraTakeover,
        (events.single() as PinchEvent.Cancel).reason,
      )
    } finally {
      fixture.input.cancel()
      fixture.target.close()
    }
  }

  @Test
  fun disabled_and_nonmatching_bindings_leave_platform_streams_unclaimed() = runTest {
    val fixture = Fixture(backgroundScope, MapGestures.None)
    assertFalse(fixture.input.onInput(PointerEventType.PanStart, sample(0)))
    assertFalse(fixture.input.onInput(PointerEventType.ScaleChange, sample(10), scaleFactor = 2.0))
    assertEquals(0, fixture.target.starts)
    fixture.input.cancel()
    val unmatched =
      Fixture(
        backgroundScope,
        MapGestures { pinchZoom { filter = PointerFilter(button = PointerButton.Secondary) } },
      )
    assertFalse(unmatched.input.onInput(PointerEventType.ScaleStart, sample(0)))
    assertEquals(0, unmatched.target.starts)
    unmatched.input.cancel()
  }

  @Test
  fun classified_wrappers_remain_routed_and_blocked_until_all_reported_contacts_lift() {
    val routing = PlatformTransformRouting()
    fun change(id: Long, pressed: Boolean, previous: Boolean) =
      PointerInputChange(
        PointerId(id),
        0,
        Offset.Zero,
        pressed,
        0,
        Offset.Zero,
        previous,
        isInitiallyConsumed = false,
        type = PointerType.Mouse,
      )
    assertTrue(routing.route(PointerEventType.Press, true, listOf(change(1, true, false))))
    routing.intercept()
    assertTrue(routing.blocked)
    assertTrue(
      routing.route(
        PointerEventType.ScaleStart,
        true,
        listOf(change(1, true, true), change(2, true, false)),
      )
    )
    assertTrue(
      routing.route(
        PointerEventType.ScaleEnd,
        true,
        listOf(change(1, true, true), change(2, false, true)),
      )
    )
    assertTrue(routing.route(PointerEventType.Move, false, listOf(change(1, true, true))))
    assertTrue(routing.blocked)
    assertTrue(routing.route(PointerEventType.Release, false, listOf(change(1, false, true))))
    assertFalse(routing.blocked)
    assertFalse(routing.hasContacts)
    assertFalse(routing.route(PointerEventType.Press, false, listOf(change(1, true, false))))
  }

  private class Fixture(scope: CoroutineScope, initial: MapGestures = MapGestures.Standard) {
    val target = Target()
    val routing = PlatformTransformRouting()
    var options = initial
    val input =
      MapPlatformTransform(
        target,
        initial,
        { options },
        GestureIds(),
        scope,
        routing,
        {},
      )
  }

  private class Target : GestureTarget {
    var starts = 0
    var ends = 0
    val moves = mutableListOf<Offset>()
    val scales = mutableListOf<Pair<Double, DpOffset?>>()
    private var active: GestureToken? = null

    override fun getCameraPosition() = CameraPosition()

    override fun cancelTransitions() = Unit

    override fun onGestureStarted(): GestureToken {
      active?.let {
        it.cancel()
        it.job?.cancel()
        onGestureEnded(it)
      }
      return GestureToken((++starts).toLong()).also { active = it }
    }

    override fun onGestureEnded(token: GestureToken) {
      if (token.completion.isCompleted) return
      ends++
      token.complete()
      if (active === token) active = null
    }

    fun close() {
      active?.let {
        it.job?.cancel()
        onGestureEnded(it)
      }
    }

    override fun moveBy(
      deltaX: Double,
      deltaY: Double,
      duration: Duration,
      gestureToken: GestureToken?,
    ) {
      assertTrue(gestureToken?.acceptsCommands == true)
      moves += Offset(deltaX.toFloat(), deltaY.toFloat())
    }

    override fun scaleBy(
      scale: Double,
      anchor: DpOffset?,
      duration: Duration,
      gestureToken: GestureToken?,
    ) {
      assertTrue(gestureToken?.acceptsCommands == true)
      scales += scale to anchor
    }

    override fun rotateAndPitchBy(
      bearingDelta: Double,
      pitchDelta: Double,
      duration: Duration,
      anchor: DpOffset?,
      gestureToken: GestureToken?,
    ) = error("unexpected rotate")

    override suspend fun moveByAwaitingTransition(
      deltaX: Double,
      deltaY: Double,
      duration: Duration,
      gestureToken: GestureToken,
    ): Unit = error("platform pan adds no momentum")

    override suspend fun scaleByAwaitingTransition(
      scale: Double,
      anchor: DpOffset?,
      duration: Duration,
      gestureToken: GestureToken,
    ): Unit = error("platform scale adds no momentum")

    override suspend fun rotateAndPitchByAwaitingTransition(
      bearingDelta: Double,
      pitchDelta: Double,
      duration: Duration,
      gestureToken: GestureToken,
      anchor: DpOffset?,
    ): Unit = error("unexpected rotate")
  }

  companion object {
    private fun sample(time: Long) =
      GesturePointerSample(
        0,
        time,
        DpOffset(10.dp, 20.dp),
        null,
        setOf(PointerType.Mouse),
        emptySet(),
        emptySet(),
      )
  }
}
