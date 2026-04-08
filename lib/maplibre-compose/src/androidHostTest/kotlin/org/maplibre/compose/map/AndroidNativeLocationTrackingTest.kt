package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.compose.location.NativeLocationPuck
import org.maplibre.compose.location.UserTrackingMode

class AndroidNativeLocationTrackingTest {
  @Test
  fun shouldMapTrackingModeToCameraMode() {
    assertEquals(CameraMode.NONE, UserTrackingMode.None.toCameraMode())
    assertEquals(CameraMode.TRACKING, UserTrackingMode.Follow.toCameraMode())
    assertEquals(CameraMode.TRACKING_GPS, UserTrackingMode.FollowWithCourse.toCameraMode())
  }

  @Test
  fun shouldMapCameraModeBackToTrackingMode() {
    assertEquals(UserTrackingMode.None, CameraMode.NONE.toUserTrackingMode())
    assertEquals(UserTrackingMode.Follow, CameraMode.TRACKING.toUserTrackingMode())
    assertEquals(UserTrackingMode.FollowWithCourse, CameraMode.TRACKING_GPS.toUserTrackingMode())
  }

  @Test
  fun shouldMapRenderModeForNativePuck() {
    assertEquals(RenderMode.NORMAL, NativeLocationPuck.None.toRenderMode(UserTrackingMode.None))
    assertEquals(
      RenderMode.NORMAL,
      NativeLocationPuck.Default.toRenderMode(UserTrackingMode.Follow),
    )
    assertEquals(
      RenderMode.GPS,
      NativeLocationPuck.Default.toRenderMode(UserTrackingMode.FollowWithCourse),
    )
  }
}
