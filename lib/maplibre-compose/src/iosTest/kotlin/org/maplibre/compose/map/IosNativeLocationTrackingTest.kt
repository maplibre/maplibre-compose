package org.maplibre.compose.map

import MapLibre.MLNUserTrackingModeFollow
import MapLibre.MLNUserTrackingModeFollowWithCourse
import MapLibre.MLNUserTrackingModeNone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import org.maplibre.compose.location.UserTrackingMode
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.Foundation.NSDate

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

  @Test
  fun shouldNudgeCourseTrackingLocation() {
    val source = testLocation(latitude = 48.20849, longitude = 16.37208, course = 120.0)

    val nudged = source.withCourseNudge()

    val sourceLatitude = source.coordinate.useContents { latitude }
    val sourceLongitude = source.coordinate.useContents { longitude }
    nudged.coordinate.useContents {
      assertNotEquals(sourceLatitude, latitude)
      assertNotEquals(sourceLongitude, longitude)
    }
    assertEquals(source.course, nudged.course)
  }

  @Test
  fun shouldLeaveInvalidCourseLocationUnchanged() {
    val source = testLocation(latitude = 48.20849, longitude = 16.37208, course = -1.0)

    val nudged = source.withCourseNudge()

    val sourceLatitude = source.coordinate.useContents { latitude }
    val sourceLongitude = source.coordinate.useContents { longitude }
    nudged.coordinate.useContents {
      assertEquals(sourceLatitude, latitude)
      assertEquals(sourceLongitude, longitude)
    }
    assertEquals(source.course, nudged.course)
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun testLocation(latitude: Double, longitude: Double, course: Double): CLLocation =
  CLLocation(
    coordinate = CLLocationCoordinate2DMake(latitude = latitude, longitude = longitude),
    altitude = 180.0,
    horizontalAccuracy = 3.0,
    verticalAccuracy = -1.0,
    course = course,
    speed = 13.0,
    timestamp = NSDate(),
  )
