package org.maplibre.compose.location

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
    course =
      BearingWithAccuracy(
        value = Bearing.North + course.degrees,
        accuracy = courseAccuracy.degrees,
      ),
    speed = SpeedWithAccuracy(distancePerSecond = speed.meters, accuracy = speedAccuracy.meters),
    timestamp =
      (-timestamp.timeIntervalSinceNow).seconds.let { age -> TimeSource.Monotonic.markNow() - age },
  )
