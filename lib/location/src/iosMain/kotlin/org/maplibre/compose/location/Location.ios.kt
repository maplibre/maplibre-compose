package org.maplibre.compose.location

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.cinterop.useContents
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.meters
import platform.CoreLocation.CLLocation
import platform.Foundation.timeIntervalSinceNow

public fun CLLocation.asMapLibreLocation(): Location =
  Location(
    position =
      coordinate.useContents {
        PositionWithAccuracy(
          value = Position(longitude = longitude, latitude = latitude, altitude = altitude),
          accuracy = horizontalAccuracy.meters,
        )
      },
    altitudeAccuracy = if (verticalAccuracy >= 0.0) verticalAccuracy.meters else null,
    course =
      if (course >= 0.0) {
        BearingWithAccuracy(
          value = Bearing.North + course.degrees,
          accuracy = if (courseAccuracy >= 0.0) courseAccuracy.degrees else null,
        )
      } else {
        null
      },
    speed =
      if (speed >= 0.0) {
        SpeedWithAccuracy(
          distancePerSecond = speed.meters,
          accuracy = if (speedAccuracy >= 0.0) speedAccuracy.meters else null,
        )
      } else {
        null
      },
    timestamp = TimeSource.Monotonic.markNow() - ageAtReceipt(),
  )

internal fun CLLocation.ageAtReceipt(): Duration =
  (-timestamp.timeIntervalSinceNow).coerceAtLeast(0.0).seconds
