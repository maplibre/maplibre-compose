package org.maplibre.compose.location

import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.cinterop.useContents
import org.maplibre.spatialk.geojson.Position
import platform.CoreLocation.CLLocation
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
