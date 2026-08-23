package org.maplibre.compose.hms

import com.huawei.hms.location.LocationRequest as HmsLocationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationRequest
import org.maplibre.spatialk.units.extensions.meters

class HmsLocationRequestTest {

  @Test
  fun mapsRequestFieldsAndSelectsWgs84() {
    val request =
      LocationRequest(
          accuracy = LocationAccuracy.Balanced,
          minimumInterval = 3.seconds,
          minimumDistance = 7.meters,
        )
        .asHmsLocationRequest()

    assertEquals(HmsLocationRequest.PRIORITY_BALANCED_POWER_ACCURACY, request.priority)
    assertEquals(3_000L, request.interval)
    assertEquals(3_000L, request.fastestInterval)
    assertEquals(7f, request.smallestDisplacement)
    assertEquals(HmsLocationRequest.COORDINATE_TYPE_WGS84, request.coordinateType)
  }

  @Test
  fun mapsEveryAccuracyPriority() {
    val expected =
      mapOf(
        LocationAccuracy.BestForNavigation to HmsLocationRequest.PRIORITY_HIGH_ACCURACY,
        LocationAccuracy.High to HmsLocationRequest.PRIORITY_HIGH_ACCURACY,
        LocationAccuracy.Balanced to HmsLocationRequest.PRIORITY_BALANCED_POWER_ACCURACY,
        LocationAccuracy.Low to HmsLocationRequest.PRIORITY_LOW_POWER,
        LocationAccuracy.Lowest to HmsLocationRequest.PRIORITY_NO_POWER,
      )

    expected.forEach { (accuracy, priority) ->
      assertEquals(priority, LocationRequest(accuracy = accuracy).asHmsLocationRequest().priority)
    }
  }
}
