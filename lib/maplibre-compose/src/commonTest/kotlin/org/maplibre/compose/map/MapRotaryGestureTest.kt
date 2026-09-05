package org.maplibre.compose.map

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class MapRotaryGestureTest {
  private val map = GestureTestFixture()

  @AfterTest fun closeMap() = map.close()

  @Test
  fun direction_anchor_burst_identity_and_idle_completion() = runTest {
    val events = mutableListOf<RotaryGestureEvent>()
    val fixture = Fixture(backgroundScope, RotaryZoomBinding(onEvent = { events += it }))
    assertTrue(fixture.input.onSample(24f, 0f, 0))
    runCurrent()
    advanceTimeBy(100)
    assertTrue(fixture.input.onSample(-24f, 0f, 0))
    runCurrent()
    assertTrue(fixture.target.scaleCalls[0].scale < 1.0)
    assertTrue(fixture.target.scaleCalls[1].scale > 1.0)
    assertTrue(fixture.target.scaleCalls.all { it.anchor == null })
    assertEquals(1, fixture.target.startedCount)
    assertEquals(events[0].gestureId, events[1].gestureId)
    advanceTimeBy(199)
    runCurrent()
    assertEquals(0, fixture.target.endedCount)
    advanceTimeBy(1)
    runCurrent()
    assertEquals(1, fixture.target.endedCount)
    assertEquals(2, fixture.target.scaleCalls.size)
    fixture.input.onSample(24f, 0f, 0)
    assertTrue(events.last().gestureId != events.first().gestureId)
    fixture.input.cancel()
  }

  @Test
  fun invalid_or_disabled_samples_do_not_claim_camera() = runTest {
    for (notch in listOf(0f, -24f, Float.NaN, Float.POSITIVE_INFINITY)) {
      val fixture = Fixture(backgroundScope, notch = notch)
      assertFalse(fixture.input.onSample(24f, 0f, 0))
      assertEquals(0, fixture.target.startedCount)
    }
    val fixture = Fixture(backgroundScope)
    for (vertical in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY)) {
      assertFalse(fixture.input.onSample(vertical, 0f, 0))
    }
    assertFalse(fixture.input.onSample(24f, Float.NaN, 0))
    fixture.binding = fixture.binding.copy(enabled = false)
    assertFalse(fixture.input.onSample(24f, 0f, 0))
    assertEquals(0, fixture.target.startedCount)
  }

  @Test
  fun callback_updates_apply_within_the_burst_and_takeover_stops_the_old_response() = runTest {
    val observed = mutableListOf<String>()
    val fixture = Fixture(backgroundScope, RotaryZoomBinding(onEvent = { observed += "initial" }))
    fixture.input.onSample(24f, 0f, 0)
    fixture.binding = fixture.binding.copy(onEvent = { observed += "updated" })
    fixture.input.onSample(24f, 0f, 0)
    assertEquals(listOf("initial", "updated"), observed)
    assertEquals(1, fixture.target.startedCount)
    fixture.binding = fixture.binding.copy(onEvent = { fixture.target.onGestureStarted() })
    fixture.input.onSample(24f, 0f, 0)
    runCurrent()
    assertEquals(2, fixture.target.scaleCalls.size)
    assertEquals(2, fixture.target.startedCount)
    assertEquals(1, fixture.target.endedCount)
    advanceTimeBy(250)
    runCurrent()
    assertEquals(1, fixture.target.endedCount, "old idle job ended the replacement session")
    map.close()
  }

  @Test
  fun throwing_observer_cancels_the_burst_before_response_and_a_later_sample_recovers() = runTest {
    val fixture =
      Fixture(backgroundScope, RotaryZoomBinding(onEvent = { error("observer failed") }))
    assertFailsWith<IllegalStateException> { fixture.input.onSample(24f, 0f, 0) }
    assertEquals(1, fixture.target.endedCount)
    assertTrue(fixture.target.scaleCalls.isEmpty())
    fixture.binding = RotaryZoomBinding()
    assertTrue(fixture.input.onSample(24f, 0f, 0))
    assertEquals(2, fixture.target.startedCount)
    assertEquals(1, fixture.target.scaleCalls.size)
    fixture.input.cancel()
    advanceTimeBy(250)
    runCurrent()
    assertEquals(2, fixture.target.endedCount)
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

  private inner class Fixture(
    scope: CoroutineScope,
    var binding: RotaryZoomBinding = RotaryZoomBinding(),
    notch: Float = 24f,
  ) {
    val target = map.target
    val input =
      MapRotaryGesture(target, { binding }, GestureIds(), notch, scope, GestureContinuation(scope))
  }
}
