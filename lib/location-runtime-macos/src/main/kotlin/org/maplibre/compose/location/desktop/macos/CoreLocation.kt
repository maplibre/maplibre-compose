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

internal const val CL_ERROR_DOMAIN = "kCLErrorDomain"
internal const val CL_ERROR_LOCATION_UNKNOWN = 0L
internal const val CL_ERROR_DENIED = 1L
internal const val CL_ERROR_NETWORK = 2L
internal const val CL_ERROR_PROMPT_DECLINED = 18L

internal const val CL_AUTHORIZATION_NOT_DETERMINED = 0L
internal const val CL_AUTHORIZATION_RESTRICTED = 1L
internal const val CL_AUTHORIZATION_DENIED = 2L
internal const val CL_AUTHORIZATION_AUTHORIZED_ALWAYS = 3L
internal const val CL_AUTHORIZATION_AUTHORIZED_WHEN_IN_USE = 4L

internal const val CL_ACCURACY_AUTHORIZATION_FULL = 0L

internal const val CL_LOCATION_ACCURACY_BEST_FOR_NAVIGATION = -2.0
internal const val CL_LOCATION_ACCURACY_BEST = -1.0
internal const val CL_LOCATION_ACCURACY_HUNDRED_METERS = 100.0
internal const val CL_LOCATION_ACCURACY_KILOMETER = 1000.0

/** Fallback when `kCLLocationAccuracyReduced` cannot be resolved, such as off macOS. */
internal const val CL_LOCATION_ACCURACY_REDUCED_FALLBACK = 6_380_000.0

internal val CL_LOCATION_ACCURACY_REDUCED: Double by lazy {
  ObjectiveC.exportedDoubleOrNull("kCLLocationAccuracyReduced")
    ?: CL_LOCATION_ACCURACY_REDUCED_FALLBACK
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
}

internal fun LocationAccuracy.toDesiredAccuracy(): Double =
  when (this) {
    LocationAccuracy.BestForNavigation -> CL_LOCATION_ACCURACY_BEST_FOR_NAVIGATION
    LocationAccuracy.High -> CL_LOCATION_ACCURACY_BEST
    LocationAccuracy.Balanced -> CL_LOCATION_ACCURACY_HUNDRED_METERS
    LocationAccuracy.Low -> CL_LOCATION_ACCURACY_KILOMETER
    LocationAccuracy.Lowest -> CL_LOCATION_ACCURACY_REDUCED
  }

internal fun CoreLocationError.asUnavailableReason(
  locationServicesEnabled: Boolean
): LocationUnavailableReason =
  when {
    domain != CL_ERROR_DOMAIN -> LocationUnavailableReason.UnexpectedFailure
    code == CL_ERROR_DENIED ->
      if (locationServicesEnabled) {
        LocationUnavailableReason.PermissionDenied
      } else {
        LocationUnavailableReason.ServicesDisabled
      }
    code == CL_ERROR_PROMPT_DECLINED -> LocationUnavailableReason.PermissionDenied
    code == CL_ERROR_LOCATION_UNKNOWN || code == CL_ERROR_NETWORK ->
      LocationUnavailableReason.TemporarilyUnavailable
    else -> LocationUnavailableReason.UnexpectedFailure
  }

internal fun CoreLocationMeasurement.asMapLibreLocationMeasurement(): LocationMeasurement =
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
    CL_AUTHORIZATION_AUTHORIZED_ALWAYS,
    CL_AUTHORIZATION_AUTHORIZED_WHEN_IN_USE ->
      LocationPermission.Granted(
        if (accuracyAuthorization == CL_ACCURACY_AUTHORIZATION_FULL) {
          LocationAccuracyAuthorization.Precise
        } else {
          LocationAccuracyAuthorization.Approximate
        }
      )
    CL_AUTHORIZATION_NOT_DETERMINED -> LocationPermission.NotGranted(canRequest = true)
    CL_AUTHORIZATION_DENIED,
    CL_AUTHORIZATION_RESTRICTED -> LocationPermission.NotGranted(canRequest = false)
    else -> LocationPermission.NotGranted(canRequest = null)
  }
