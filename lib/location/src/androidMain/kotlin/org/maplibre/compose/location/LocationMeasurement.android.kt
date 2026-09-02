package org.maplibre.compose.location

import android.location.Location as AndroidLocation
import android.os.Build
import android.os.SystemClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.meters

public fun AndroidLocation.asMapLibreLocationMeasurement(): LocationMeasurement =
  LocationMeasurement(
    position =
      Position(
        longitude = longitude,
        latitude = latitude,
        altitude = if (hasAltitude()) altitude else null,
      ),
    horizontalAccuracy = if (hasAccuracy()) accuracy.toDouble().meters else null,
    altitudeAccuracy =
      if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasAltitude() && hasVerticalAccuracy()
      ) {
        verticalAccuracyMeters.toDouble().meters
      } else {
        null
      },
    distancePerSecond = if (hasSpeed()) speed.toDouble().meters else null,
    distancePerSecondAccuracy =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasSpeed() && hasSpeedAccuracy()) {
        speedAccuracyMetersPerSecond.toDouble().meters
      } else {
        null
      },
    course = if (hasBearing()) Bearing.North + bearing.toDouble().degrees else null,
    courseAccuracy =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasBearing() && hasBearingAccuracy()) {
        bearingAccuracyDegrees.toDouble().degrees
      } else {
        null
      },
    measuredAt = Instant.fromEpochMilliseconds(time),
  )

/** Converts this platform location and preserves its monotonic age at receipt. */
public fun AndroidLocation.asMapLibreLocationUpdate(): LocationEvent.Update =
  LocationEvent.Update(
    measurement = asMapLibreLocationMeasurement(),
    measurementMark = TimeSource.Monotonic.markNow() - ageAtReceipt(),
  )

internal fun AndroidLocation.ageAtReceipt(): Duration =
  (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos).coerceAtLeast(0).nanoseconds
