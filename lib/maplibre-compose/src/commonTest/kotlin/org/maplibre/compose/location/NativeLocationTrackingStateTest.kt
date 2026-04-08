package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeLocationTrackingStateTest {
  @Test
  fun shouldUpdateTrackingModeFromApp() {
    val state = NativeLocationTrackingState(UserTrackingMode.None)

    state.trackingMode = UserTrackingMode.FollowWithCourse

    assertEquals(UserTrackingMode.FollowWithCourse, state.trackingMode)
  }

  @Test
  fun shouldUpdateTrackingModeFromMap() {
    val state = NativeLocationTrackingState(UserTrackingMode.Follow)

    state.setFromMap(UserTrackingMode.None)

    assertEquals(UserTrackingMode.None, state.trackingMode)
  }
}
