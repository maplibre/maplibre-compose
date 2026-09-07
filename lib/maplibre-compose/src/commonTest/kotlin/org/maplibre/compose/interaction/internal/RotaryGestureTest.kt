package org.maplibre.compose.interaction.internal

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.map.GestureTestFixture

@OptIn(ExperimentalCoroutinesApi::class)
class MapRotaryGestureTest {
  private val map = GestureTestFixture()

  @AfterTest fun closeMap() = map.close()

  @Test
  fun invalid_or_disabled_samples_do_not_claim_camera() = runTest {
    for (notch in listOf(0f, -24f, Float.NaN, Float.POSITIVE_INFINITY)) {
      val input = RotaryGesture(map.target, RotaryBinding(), notch, backgroundScope)
      assertFalse(input.onSample(24f))
    }
    val input = RotaryGesture(map.target, RotaryBinding(), 24f, backgroundScope)
    for (vertical in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY)) {
      assertFalse(input.onSample(vertical))
    }
    val disabled = RotaryGesture(map.target, RotaryBinding(enabled = false), 24f, backgroundScope)
    assertFalse(disabled.onSample(24f))
    assertEquals(0, map.target.startedCount)
  }

  @Test
  fun focus_notifications_replay_current_engagement_and_balance_focus_loss() {
    val notifications = mutableListOf<Boolean>()
    val focus = InputFocus { notifications += it }
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
}
