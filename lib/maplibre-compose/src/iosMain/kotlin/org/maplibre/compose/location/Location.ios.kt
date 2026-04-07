package org.maplibre.compose.location

import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.cinterop.useContents
import org.maplibre.compose.util.toCLLocationCoordinate2D
import org.maplibre.spatialk.geojson.Position
import platform.CoreLocation.CLLocation
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSinceNow

public fun CLLocation.asMapLibreLocation(): Location =
  Location(
    position =
      coordinate.useContents {
        Position(longitude = longitude, latitude = latitude, altitude = altitude)
      },
    accuracy = horizontalAccuracy,
    bearing = course,
    bearingAccuracy = courseAccuracy,
    speed = speed,
    speedAccuracy = speedAccuracy,
    timestamp =
      (-timestamp.timeIntervalSinceNow).seconds.let { age -> TimeSource.Monotonic.markNow() - age },
  )

public fun Location.asClLocation(): CLLocation {
  return CLLocation(
    coordinate = position.toCLLocationCoordinate2D(),
    altitude = position.altitude ?: 0.0,
    horizontalAccuracy = accuracy,
    verticalAccuracy = -1.0,
    course = bearing ?: -1.0,
    speed = speed ?: -1.0,
    timestamp = NSDate(),
  )
}
