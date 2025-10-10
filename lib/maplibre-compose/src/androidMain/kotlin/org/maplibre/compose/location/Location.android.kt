package org.maplibre.compose.location

import android.location.Location as AndroidLocation
import android.os.Build
import android.os.SystemClock
import io.github.dellisd.spatialk.geojson.Position
import kotlin.time.Duration.Companion.nanoseconds

public fun AndroidLocation.asMapLibreLocation(): Location =
  Location(
    position = Position(longitude = longitude, latitude = latitude, altitude = altitude),
    accuracy = accuracy.toDouble(),
    bearing = bearing.toDouble(),
    bearingAccuracy =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        bearingAccuracyDegrees.toDouble()
      } else {
        0.0
      },
    age = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos).nanoseconds,
  )
