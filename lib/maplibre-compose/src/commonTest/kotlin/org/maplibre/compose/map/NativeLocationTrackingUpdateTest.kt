package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.maplibre.compose.location.NativeLocationPuck
import org.maplibre.compose.location.UserTrackingMode

class NativeLocationTrackingUpdateTest {
  @Test
  fun shouldBeDisabledWhenTrackingModeAndPuckAreNone() {
    val update =
      NativeLocationTrackingUpdate(
        location = null,
        trackingMode = UserTrackingMode.None,
        puck = NativeLocationPuck.None,
      )

    assertFalse(update.isEnabled)
  }

  @Test
  fun shouldBeEnabledWhenTrackingModeIsActive() {
    val update =
      NativeLocationTrackingUpdate(
        location = null,
        trackingMode = UserTrackingMode.Follow,
        puck = NativeLocationPuck.None,
      )

    assertTrue(update.isEnabled)
  }

  @Test
  fun shouldBeEnabledWhenNativePuckIsActive() {
    val update =
      NativeLocationTrackingUpdate(
        location = null,
        trackingMode = UserTrackingMode.None,
        puck = NativeLocationPuck.Default,
      )

    assertTrue(update.isEnabled)
  }
}
