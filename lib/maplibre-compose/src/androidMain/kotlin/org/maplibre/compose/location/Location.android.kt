package org.maplibre.compose.location

import android.location.Location as AndroidLocation
import android.os.Build
import android.os.SystemClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource
import org.maplibre.spatialk.geojson.Position

public fun AndroidLocation.asMapLibreLocation(): Location =
  Location(
    position = Position(longitude = longitude, latitude = latitude, altitude = altitude),
    accuracy = accuracy.toDouble(),
    bearing = if (hasBearing()) bearing.toDouble() else null,
    bearingAccuracy =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasBearingAccuracy()) {
        bearingAccuracyDegrees.toDouble()
      } else {
        null
      },
    speed = if (hasSpeed()) speed.toDouble() else null,
    speedAccuracy =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasSpeedAccuracy()) {
        speedAccuracyMetersPerSecond.toDouble()
      } else {
        null
      },
    timestamp =
      (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos).nanoseconds.let { age ->
        TimeSource.Monotonic.markNow() - age
      },
  )

public fun Location.asAndroidLocation(
  provider: String = "maplibre-compose-native-tracking"
): AndroidLocation {
  val age = timestamp.elapsedNow().coerceAtLeast(Duration.ZERO)

  return AndroidLocation(provider).apply {
    latitude = position.latitude
    longitude = position.longitude
    altitude = position.altitude ?: 0.0
    accuracy = this@asAndroidLocation.accuracy.toFloat()

    this@asAndroidLocation.bearing?.toFloat()?.let { this.bearing = it }
    this@asAndroidLocation.speed?.toFloat()?.let { this.speed = it }

    time = System.currentTimeMillis() - age.inWholeMilliseconds

    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - age.inWholeNanoseconds

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      this@asAndroidLocation.bearingAccuracy?.toFloat()?.let { bearingAccuracyDegrees = it }
      this@asAndroidLocation.speedAccuracy?.toFloat()?.let { speedAccuracyMetersPerSecond = it }
    }
  }
}
