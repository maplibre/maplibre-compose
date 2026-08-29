package org.maplibre.compose.location

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.cinterop.useContents
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.meters
import platform.CoreLocation.CLLocation
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeIntervalSinceNow

public fun CLLocation.asMapLibreLocationReading(): LocationReading =
  LocationReading(
    position =
      coordinate.useContents {
        Position(longitude = longitude, latitude = latitude, altitude = altitude)
      },
    horizontalAccuracy = horizontalAccuracy.meters,
    altitudeAccuracy = if (verticalAccuracy >= 0.0) verticalAccuracy.meters else null,
    course = if (course >= 0.0) Bearing.North + course.degrees else null,
    courseAccuracy = if (course >= 0.0 && courseAccuracy >= 0.0) courseAccuracy.degrees else null,
    speed = if (speed >= 0.0) speed.meters else null,
    speedAccuracy = if (speed >= 0.0 && speedAccuracy >= 0.0) speedAccuracy.meters else null,
    measuredAt = Instant.fromEpochMilliseconds((timestamp.timeIntervalSince1970 * 1_000).toLong()),
  )

internal fun CLLocation.ageAtReceipt(): Duration =
  (-timestamp.timeIntervalSinceNow).coerceAtLeast(0.0).seconds
