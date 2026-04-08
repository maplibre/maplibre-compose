package org.maplibre.compose.map

import MapLibre.MLNUserTrackingModeFollow
import MapLibre.MLNUserTrackingModeFollowWithCourse
import MapLibre.MLNUserTrackingModeNone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.ExperimentalForeignApi
import org.maplibre.compose.location.UserTrackingMode

@OptIn(ExperimentalForeignApi::class)
class IosNativeLocationTrackingTest {
  @Test
  fun shouldMapTrackingModeToNativeTrackingMode() {
    assertEquals(MLNUserTrackingModeNone, UserTrackingMode.None.toMlnUserTrackingMode())
    assertEquals(MLNUserTrackingModeFollow, UserTrackingMode.Follow.toMlnUserTrackingMode())
    assertEquals(
      MLNUserTrackingModeFollowWithCourse,
      UserTrackingMode.FollowWithCourse.toMlnUserTrackingMode(),
    )
  }

  @Test
  fun shouldMapNativeTrackingModeBackToSharedTrackingMode() {
    assertEquals(UserTrackingMode.None, MLNUserTrackingModeNone.toUserTrackingMode())
    assertEquals(UserTrackingMode.Follow, MLNUserTrackingModeFollow.toUserTrackingMode())
    assertEquals(
      UserTrackingMode.FollowWithCourse,
      MLNUserTrackingModeFollowWithCourse.toUserTrackingMode(),
    )
  }
}
