package org.maplibre.compose.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.camera.CameraPosition

@OptIn(ExperimentalCoroutinesApi::class)
class MapRotaryGestureTest {
  @Test
  fun direction_anchor_burst_identity_and_idle_completion() = runTest {
    val events = mutableListOf<RotaryGestureEvent>()
    val fixture = Fixture(backgroundScope, RotaryZoomBinding(onEvent = { events += it }))
    assertTrue(fixture.input.onSample(24f, 0f, 0))
    runCurrent()
    advanceTimeBy(100)
    assertTrue(fixture.input.onSample(-24f, 0f, 0))
    runCurrent()
    assertTrue(fixture.target.scales[0].first < 1.0)
    assertTrue(fixture.target.scales[1].first > 1.0)
    assertTrue(fixture.target.scales.all { it.second == null })
    assertEquals(1, fixture.target.starts)
    assertEquals(events[0].gestureId, events[1].gestureId)
    advanceTimeBy(199)
    runCurrent()
    assertEquals(0, fixture.target.ends)
    advanceTimeBy(1)
    runCurrent()
    assertEquals(1, fixture.target.ends)
    assertEquals(2, fixture.target.scales.size)
    fixture.input.onSample(24f, 0f, 0)
    assertTrue(events.last().gestureId != events.first().gestureId)
    fixture.input.cancel()
  }

  @Test
  fun invalid_or_disabled_samples_do_not_claim_camera() = runTest {
    for (notch in listOf(0f, -24f, Float.NaN, Float.POSITIVE_INFINITY)) {
      val fixture = Fixture(backgroundScope, notch = notch)
      assertFalse(fixture.input.onSample(24f, 0f, 0))
      assertEquals(0, fixture.target.starts)
    }
    val fixture = Fixture(backgroundScope)
    for (vertical in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY)) {
      assertFalse(fixture.input.onSample(vertical, 0f, 0))
    }
    assertFalse(fixture.input.onSample(24f, Float.NaN, 0))
    fixture.binding = fixture.binding.copy(enabled = false)
    assertFalse(fixture.input.onSample(24f, 0f, 0))
    assertEquals(0, fixture.target.starts)
  }

  @Test
  fun callback_updates_apply_within_the_burst_and_takeover_stops_the_old_response() = runTest {
    val observed = mutableListOf<String>()
    val fixture = Fixture(backgroundScope, RotaryZoomBinding(onEvent = { observed += "initial" }))
    fixture.input.onSample(24f, 0f, 0)
    fixture.binding = fixture.binding.copy(onEvent = { observed += "updated" })
    fixture.input.onSample(24f, 0f, 0)
    assertEquals(listOf("initial", "updated"), observed)
    assertEquals(1, fixture.target.starts)
    fixture.binding = fixture.binding.copy(onEvent = { fixture.target.onGestureStarted() })
    fixture.input.onSample(24f, 0f, 0)
    runCurrent()
    assertEquals(2, fixture.target.scales.size)
    assertEquals(2, fixture.target.starts)
    assertEquals(1, fixture.target.ends)
    advanceTimeBy(250)
    runCurrent()
    assertEquals(1, fixture.target.ends, "old idle job ended the replacement session")
    fixture.target.close()
  }

  @Test
  fun throwing_observer_cancels_the_burst_before_response_and_a_later_sample_recovers() = runTest {
    val fixture =
      Fixture(backgroundScope, RotaryZoomBinding(onEvent = { error("observer failed") }))
    assertFailsWith<IllegalStateException> { fixture.input.onSample(24f, 0f, 0) }
    assertEquals(1, fixture.target.ends)
    assertTrue(fixture.target.scales.isEmpty())
    fixture.binding = RotaryZoomBinding()
    assertTrue(fixture.input.onSample(24f, 0f, 0))
    assertEquals(2, fixture.target.starts)
    assertEquals(1, fixture.target.scales.size)
    fixture.input.cancel()
    advanceTimeBy(250)
    runCurrent()
    assertEquals(2, fixture.target.ends)
  }

  @Test
  fun focus_notifications_replay_current_engagement_and_balance_focus_loss() {
    val notifications = mutableListOf<Boolean>()
    val focus = MapInputFocus { notifications += it }
    focus.hasKeyBindings = true
    focus.replay()
    focus.onFocusChanged(true)
    focus.engage(byKey = true)
    focus.replay()
    focus.onFocusChanged(false)
    focus.replay()
    assertEquals(listOf(false, true, true, false, false), notifications)
    assertFalse(focus.consumesBack)
  }

  private class Fixture(
    scope: CoroutineScope,
    var binding: RotaryZoomBinding = RotaryZoomBinding(),
    notch: Float = 24f,
  ) {
    val target = Target()
    val input =
      MapRotaryGesture(target, { binding }, GestureIds(), notch, scope, GestureContinuation(scope))
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
    ): Unit = error("rotary never pans")

    override suspend fun scaleByAwaitingTransition(
      scale: Double,
      anchor: DpOffset?,
      duration: Duration,
      gestureToken: GestureToken,
    ): Unit = error("rotary adds no momentum")

    override suspend fun rotateAndPitchByAwaitingTransition(
      bearingDelta: Double,
      pitchDelta: Double,
      duration: Duration,
      gestureToken: GestureToken,
      anchor: DpOffset?,
    ): Unit = error("unexpected rotate")
  }
}
