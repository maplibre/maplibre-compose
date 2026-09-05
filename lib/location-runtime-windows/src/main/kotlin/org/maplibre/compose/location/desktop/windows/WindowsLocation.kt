package org.maplibre.compose.location.desktop.windows

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationAccuracyAuthorization
import org.maplibre.compose.location.LocationMeasurement
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.meters

internal enum class WindowsAccessStatus {
  DeniedBySystem,
  NotDeclared,
  DeniedByUser,
  UserPromptRequired,
  Allowed,
  Unknown,
}

internal enum class WindowsPositionStatus {
  Ready,
  Initializing,
  NoData,
  Disabled,
  NotInitialized,
  NotAvailable,
  Unknown,
}

internal data class WindowsLocationMeasurement(
  val latitude: Double,
  val longitude: Double,
  val altitudeMeters: Double?,
  val horizontalAccuracyMeters: Double,
  val verticalAccuracyMeters: Double?,
  val headingDegrees: Double?,
  val speedMetersPerSecond: Double?,
  val windowsTimestampTicks: Long,
)

internal data class WindowsLocationConfiguration(
  val desiredAccuracyMeters: Int,
  val reportIntervalMilliseconds: Int,
)

internal fun LocationAccuracy.toDesiredAccuracyMeters(): Int =
  when (this) {
    LocationAccuracy.BestForNavigation -> 1
    LocationAccuracy.High -> 10
    LocationAccuracy.Balanced -> 100
    LocationAccuracy.Low -> 1_000
    LocationAccuracy.Lowest -> 5_000
  }

internal fun Duration.toReportIntervalMilliseconds(): Int =
  if (this == Duration.ZERO) {
    0
  } else {
    inWholeMilliseconds.coerceAtLeast(1).coerceAtMost(UInt.MAX_VALUE.toLong()).toInt()
  }

internal fun WindowsAccessStatus.asLocationPermission(): LocationPermission =
  when (this) {
    WindowsAccessStatus.Allowed -> LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
    WindowsAccessStatus.UserPromptRequired -> LocationPermission.NotGranted(canRequest = true)
    WindowsAccessStatus.DeniedBySystem,
    WindowsAccessStatus.NotDeclared,
    WindowsAccessStatus.DeniedByUser -> LocationPermission.NotGranted(canRequest = false)
    WindowsAccessStatus.Unknown -> LocationPermission.NotGranted(canRequest = null)
  }

internal fun WindowsPositionStatus.asUnavailableReason(
  permission: LocationPermission
): LocationUnavailableReason? =
  when (this) {
    WindowsPositionStatus.Ready -> null
    WindowsPositionStatus.Initializing,
    WindowsPositionStatus.NoData,
    WindowsPositionStatus.NotInitialized -> LocationUnavailableReason.TemporarilyUnavailable
    WindowsPositionStatus.Disabled ->
      if (permission is LocationPermission.Granted) {
        LocationUnavailableReason.ServicesDisabled
      } else {
        LocationUnavailableReason.PermissionDenied
      }
    WindowsPositionStatus.NotAvailable -> LocationUnavailableReason.Unsupported
    WindowsPositionStatus.Unknown -> LocationUnavailableReason.UnexpectedFailure
  }

internal fun WindowsLocationMeasurement.asMapLibreLocationMeasurement(): LocationMeasurement? {
  if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
  if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
  if (!horizontalAccuracyMeters.isFinite() || horizontalAccuracyMeters < 0.0) return null
  if (windowsTimestampTicks < WINDOWS_EPOCH_TICKS) return null

  val altitude = altitudeMeters?.takeIf(Double::isFinite)
  val verticalAccuracy =
    if (altitude == null) null
    else verticalAccuracyMeters?.takeIf { it.isFinite() && it >= 0.0 }?.meters
  val course =
    headingDegrees?.takeIf { it.isFinite() && it >= 0.0 }?.let { Bearing.North + it.degrees }
  val speed = speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0.0 }?.meters
  val capturedAtMillis = (windowsTimestampTicks - WINDOWS_EPOCH_TICKS) / TICKS_PER_MILLISECOND

  return LocationMeasurement(
    position = Position(longitude = longitude, latitude = latitude, altitude = altitude),
    horizontalAccuracy = horizontalAccuracyMeters.meters,
    altitudeAccuracy = verticalAccuracy,
    distancePerSecond = speed,
    course = course,
    measuredAt = Instant.fromEpochMilliseconds(capturedAtMillis),
  )
}

internal class WindowsLocationFilter(
  private val minimumInterval: Duration,
  private val minimumDistanceMeters: Double,
) {
  private var previousLocation: WindowsLocationMeasurement? = null

  fun shouldDeliver(measurement: WindowsLocationMeasurement): Boolean {
    val previous = previousLocation
    if (previous == null) {
      previousLocation = measurement
      return true
    }
    val elapsed =
      ((measurement.windowsTimestampTicks - previous.windowsTimestampTicks).coerceAtLeast(0) /
          TICKS_PER_MILLISECOND)
        .milliseconds
    if (elapsed < minimumInterval) return false
    if (haversineMeters(previous, measurement) < minimumDistanceMeters) return false
    previousLocation = measurement
    return true
  }
}

private fun haversineMeters(
  first: WindowsLocationMeasurement,
  second: WindowsLocationMeasurement,
): Double {
  val firstLatitude = first.latitude.toRadians()
  val secondLatitude = second.latitude.toRadians()
  val latitudeDelta = secondLatitude - firstLatitude
  val longitudeDelta = (second.longitude - first.longitude).toRadians()
  val a =
    sin(latitudeDelta / 2).let { it * it } +
      cos(firstLatitude) * cos(secondLatitude) * sin(longitudeDelta / 2).let { it * it }
  return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private fun Double.toRadians(): Double = this * PI / 180.0

internal const val WINDOWS_EPOCH_TICKS: Long = 116_444_736_000_000_000L
internal const val TICKS_PER_MILLISECOND: Long = 10_000L
private const val EARTH_RADIUS_METERS: Double = 6_371_008.8
