package org.maplibre.compose.location

import android.location.Location as AndroidLocation
import android.os.Build
import android.os.SystemClock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.meters

public fun AndroidLocation.asMapLibreLocation(): Location =
  Location(
    position =
      PositionMeasurement(
        position = Position(longitude = longitude, latitude = latitude, altitude = altitude),
        accuracy = if (hasAccuracy()) accuracy.toDouble().meters else null,
      ),
    speed =
      if (hasSpeed()) {
        SpeedMeasurement(
          distancePerSecond = speed.toDouble().meters,
          accuracy =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasSpeedAccuracy()) {
              speedAccuracyMetersPerSecond.toDouble().meters
            } else {
              null
            },
        )
      } else {
        null
      },
    course =
      if (hasBearing()) {
        BearingMeasurement(
          bearing = Bearing.North + bearing.toDouble().degrees,
          accuracy =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasBearingAccuracy()) {
              bearingAccuracyDegrees.toDouble().degrees
            } else {
              null
            },
        )
      } else {
        null
      },
    timestamp =
      (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos).nanoseconds.let { age ->
        TimeSource.Monotonic.markNow() - age
      },
  )
