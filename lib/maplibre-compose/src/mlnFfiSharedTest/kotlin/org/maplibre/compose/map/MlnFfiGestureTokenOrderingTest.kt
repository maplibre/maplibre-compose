package org.maplibre.compose.map

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle

/**
 * What gesture tokens are for, which only a backend with an owner thread can be asked.
 *
 * The rest of the camera-move contract is in [CameraMoveReportingTest], on every platform.
 */
class MlnFfiGestureTokenOrderingTest {

  @Test
  fun a_backlogged_owner_thread_orders_newer_gesture_tokens_and_ignores_stale_ends() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.pumpUntilRendered()
      fixture.settle()
      fixture.events.clear()

      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      assertTrue(
        fixture.session.postOwnerTaskForTest {
          entered.countDown()
          check(release.await(5, TimeUnit.SECONDS))
        }
      )
      assertTrue(entered.await(5, TimeUnit.SECONDS))

      val stale = fixture.session.onGestureStarted()
      fixture.session.moveBy(DRAG_STEP_DP, 0.0, gestureToken = stale)
      val latest = fixture.session.onGestureStarted()
      fixture.session.moveBy(0.0, DRAG_STEP_DP, gestureToken = latest)
      fixture.session.onGestureEnded(latest)
      fixture.session.onGestureEnded(stale)
      release.countDown()
      fixture.pump(FRAMES)

      val events = fixture.events.toList()
      assertEquals(
        1,
        events.count { it == "cameraMoveStarted(GESTURE)" },
        "both queued commands should form one gesture-attributed move: $events",
      )
      assertEquals(
        1,
        events.count { it == "cameraMoveEnded" },
        "move ended more than once: $events",
      )
      assertEquals(
        "cameraMoveEnded",
        events.last(),
        "the stale end won over the latest token: $events",
      )
    }
  }

  private companion object {
    const val DRAG_STEP_DP = 10.0

    const val FRAMES = 8
  }
}
