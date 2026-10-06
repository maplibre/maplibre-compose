package org.maplibre.compose.location.desktop.macos

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationAccuracyAuthorization
import org.maplibre.compose.location.LocationBackendAvailability
import org.maplibre.compose.location.LocationMeasurement
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.meters

internal const val kCLErrorDomain = "kCLErrorDomain"
internal const val kCLErrorLocationUnknown = 0L
internal const val kCLErrorDenied = 1L
internal const val kCLErrorNetwork = 2L
internal const val kCLErrorPromptDeclined = 18L

internal const val kCLAuthorizationStatusNotDetermined = 0L
internal const val kCLAuthorizationStatusRestricted = 1L
internal const val kCLAuthorizationStatusDenied = 2L
internal const val kCLAuthorizationStatusAuthorizedAlways = 3L
internal const val kCLAuthorizationStatusAuthorizedWhenInUse = 4L

internal const val CLAccuracyAuthorizationFullAccuracy = 0L

internal const val kCLLocationAccuracyBestForNavigation = -2.0
internal const val kCLLocationAccuracyBest = -1.0
internal const val kCLLocationAccuracyHundredMeters = 100.0
internal const val kCLLocationAccuracyKilometer = 1000.0

/** Fallback when `kCLLocationAccuracyReduced` cannot be resolved, such as off macOS. */
internal const val ReducedLocationAccuracyFallback = 6_380_000.0

internal val kCLLocationAccuracyReduced: Double by lazy {
  ObjectiveC.exportedDoubleOrNull("kCLLocationAccuracyReduced") ?: ReducedLocationAccuracyFallback
}

internal data class CoreLocationMeasurement(
  val latitude: Double,
  val longitude: Double,
  val altitude: Double,
  val horizontalAccuracy: Double,
  val verticalAccuracy: Double,
  val course: Double,
  val courseAccuracy: Double,
  val speed: Double,
  val speedAccuracy: Double,
  val ageSeconds: Double,
)

internal data class CoreLocationError(val domain: String, val code: Long)

internal interface CoreLocationDelegate {
  fun didUpdateLocations(locations: List<CoreLocationMeasurement>)

  fun didFailWithError(error: CoreLocationError)

  fun didChangeAuthorization()
}

internal interface CoreLocationManager : AutoCloseable {
  var desiredAccuracy: Double
  var distanceFilter: Double
  val location: CoreLocationMeasurement?
  val authorizationStatus: Long
  val accuracyAuthorization: Long

  fun setDelegate(delegate: CoreLocationDelegate?)

  fun startUpdatingLocation()

  fun stopUpdatingLocation()

  fun requestWhenInUseAuthorization()
}

internal interface CoreLocationClient : AutoCloseable {
  val locationServicesEnabled: Boolean

  val backendAvailability: LocationBackendAvailability
    get() = LocationBackendAvailability.Available

  fun createManager(): CoreLocationManager

  /** Runs on Core Location's owning thread, executing inline when already there. */
  fun <T> onLocationThread(action: () -> T): T
}

internal fun LocationAccuracy.toDesiredAccuracy(): Double =
  when (this) {
    LocationAccuracy.BestForNavigation -> kCLLocationAccuracyBestForNavigation
    LocationAccuracy.High -> kCLLocationAccuracyBest
    LocationAccuracy.Balanced -> kCLLocationAccuracyHundredMeters
    LocationAccuracy.Low -> kCLLocationAccuracyKilometer
    LocationAccuracy.Lowest -> kCLLocationAccuracyReduced
  }

internal fun CoreLocationError.asUnavailableReason(
  locationServicesEnabled: Boolean
): LocationUnavailableReason? =
  when {
    domain != kCLErrorDomain -> null
    code == kCLErrorDenied ->
      if (locationServicesEnabled) {
        LocationUnavailableReason.PermissionDenied
      } else {
        LocationUnavailableReason.ServicesDisabled
      }
    code == kCLErrorPromptDeclined -> LocationUnavailableReason.PermissionDenied
    code == kCLErrorLocationUnknown || code == kCLErrorNetwork ->
      LocationUnavailableReason.TemporarilyUnavailable
    else -> null
  }

internal fun CoreLocationMeasurement.asMaplibreLocationMeasurement(): LocationMeasurement =
  LocationMeasurement(
    position = Position(longitude = longitude, latitude = latitude, altitude = altitude),
    horizontalAccuracy = horizontalAccuracy.meters,
    altitudeAccuracy = if (verticalAccuracy >= 0.0) verticalAccuracy.meters else null,
    course = if (course >= 0.0) Bearing.North + course.degrees else null,
    courseAccuracy = if (course >= 0.0 && courseAccuracy >= 0.0) courseAccuracy.degrees else null,
    distancePerSecond = if (speed >= 0.0) speed.meters else null,
    distancePerSecondAccuracy =
      if (speed >= 0.0 && speedAccuracy >= 0.0) speedAccuracy.meters else null,
    measuredAt = Clock.System.now() - ageAtReceipt(),
  )

internal fun CoreLocationMeasurement.ageAtReceipt(): Duration =
  ageSeconds.coerceAtLeast(0.0).seconds

internal fun readPermission(
  authorizationStatus: Long,
  accuracyAuthorization: Long,
): LocationPermission =
  when (authorizationStatus) {
    kCLAuthorizationStatusAuthorizedAlways,
    kCLAuthorizationStatusAuthorizedWhenInUse ->
      LocationPermission.Granted(
        if (accuracyAuthorization == CLAccuracyAuthorizationFullAccuracy) {
          LocationAccuracyAuthorization.Precise
        } else {
          LocationAccuracyAuthorization.Approximate
        }
      )
    kCLAuthorizationStatusNotDetermined -> LocationPermission.NotGranted(canRequest = true)
    kCLAuthorizationStatusDenied,
    kCLAuthorizationStatusRestricted -> LocationPermission.NotGranted(canRequest = false)
    else -> LocationPermission.NotGranted(canRequest = null)
  }
