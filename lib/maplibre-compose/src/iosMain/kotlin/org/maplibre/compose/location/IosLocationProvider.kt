package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.inMeters
import org.maplibre.spatialk.units.extensions.meters
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyBestForNavigation
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.CoreLocation.kCLLocationAccuracyReduced
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSinceNow
import platform.darwin.NSObject

/**
 * A [LocationProvider] built on the [CLLocationManager] platform APIs.
 *
 * @param minDistance the minimum distance between location updates
 * @param desiredAccuracy the [DesiredAccuracy] for location updates.
 * @param enableLocation whether location updates should be requested from [CLLocationManager].
 * @param enableOrientation whether heading updates should be requested from [CLLocationManager].
 * @param orientationUpdateInterval the period at which heading updates are sampled.
 * @param coroutineScope the [CoroutineScope] used to share the [location] flow
 * @param sharingStarted parameter for [stateIn] calls
 */
@OptIn(FlowPreview::class)
public class IosLocationProvider(
  private val minDistance: Length,
  private val desiredAccuracy: DesiredAccuracy,
  private val enableLocation: Boolean,
  private val enableOrientation: Boolean,
  private val orientationUpdateInterval: Duration,
  coroutineScope: CoroutineScope,
  sharingStarted: SharingStarted,
) : LocationProvider, OrientationProvider {
  private val locationManager = CLLocationManager()

  init {
    if (
      enableLocation &&
        locationManager.authorizationStatus != kCLAuthorizationStatusAuthorizedAlways &&
        locationManager.authorizationStatus != kCLAuthorizationStatusAuthorizedWhenInUse
    ) {
      throw PermissionException()
    }
  }

  private val updates: StateFlow<ProviderUpdate?> =
    callbackFlow {
        val delegate = Delegate(channel)
        locationManager.delegate = delegate

        if (enableLocation) {
          locationManager.desiredAccuracy =
            when (desiredAccuracy) {
              DesiredAccuracy.Highest -> kCLLocationAccuracyBestForNavigation
              DesiredAccuracy.High -> kCLLocationAccuracyBest
              DesiredAccuracy.Balanced -> kCLLocationAccuracyHundredMeters
              DesiredAccuracy.Low -> kCLLocationAccuracyKilometer
              DesiredAccuracy.Lowest -> kCLLocationAccuracyReduced
            }
          locationManager.distanceFilter = minDistance.inMeters

          locationManager.stopUpdatingLocation()
          locationManager.startUpdatingLocation()
        }

        if (enableOrientation) {
          val headingAvailable = CLLocationManager.headingAvailable()
          if (headingAvailable) {
            locationManager.stopUpdatingHeading()
            locationManager.startUpdatingHeading()
          }
        }

        awaitClose {
          if (enableLocation) {
            locationManager.stopUpdatingLocation()
          }
          if (enableOrientation) {
            locationManager.stopUpdatingHeading()
          }
          locationManager.delegate = null
        }
      }
      .stateIn(coroutineScope, sharingStarted, null)

  override val location: StateFlow<Location?> =
    updates
      .runningFold(null as Location?) { location, update ->
        when (update) {
          is ProviderUpdate.LocationUpdate -> update.location
          else -> location
        }
      }
      .stateIn(coroutineScope, sharingStarted, null)

  override val orientation: StateFlow<Orientation?> =
    updates
      .runningFold(null as Orientation?) { orientation, update ->
        when (update) {
          is ProviderUpdate.OrientationUpdate -> update.orientation
          else -> orientation
        }
      }
      .sample(orientationUpdateInterval)
      .stateIn(coroutineScope, sharingStarted, null)

  private inner class Delegate(private val channel: SendChannel<ProviderUpdate>) :
    NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
      @Suppress("UNCHECKED_CAST") val locations = didUpdateLocations as? List<CLLocation>

      locations?.forEach { channel.trySend(ProviderUpdate.LocationUpdate(it.asMapLibreLocation())) }
    }

    override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
      channel.trySend(ProviderUpdate.OrientationUpdate(didUpdateHeading.asMapLibreOrientation()))
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {}
  }

  private sealed interface ProviderUpdate {
    data class LocationUpdate(val location: Location) : ProviderUpdate

    data class OrientationUpdate(val orientation: Orientation) : ProviderUpdate
  }
}

private fun CLHeading.asMapLibreOrientation(): Orientation {
  val heading = if (trueHeading >= 0.0) trueHeading else magneticHeading
  val accuracy = if (headingAccuracy >= 0.0) headingAccuracy.degrees else null
  val age = (-timestamp.timeIntervalSinceNow).seconds

  return Orientation(
    orientation = BearingWithAccuracy(value = Bearing.North + heading.degrees, accuracy = accuracy),
    timestamp = TimeSource.Monotonic.markNow() - age,
  )
}

@Composable
public actual fun rememberDefaultLocationProvider(
  updateInterval: Duration,
  desiredAccuracy: DesiredAccuracy,
  minDistance: Length,
): LocationProvider {
  return rememberIosLocationProvider(minDistance, desiredAccuracy)
}

@Composable
public fun rememberIosLocationProvider(
  minDistance: Length = 1.meters,
  desiredAccuracy: DesiredAccuracy = DesiredAccuracy.High,
  enableLocation: Boolean = true,
  enableOrientation: Boolean = false,
  orientationUpdateInterval: Duration = 1.seconds,
  coroutineScope: CoroutineScope = rememberCoroutineScope(),
  sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
): IosLocationProvider {
  return remember(
    minDistance,
    desiredAccuracy,
    enableLocation,
    enableOrientation,
    orientationUpdateInterval,
    coroutineScope,
    sharingStarted,
  ) {
    IosLocationProvider(
      minDistance = minDistance,
      desiredAccuracy = desiredAccuracy,
      enableLocation = enableLocation,
      enableOrientation = enableOrientation,
      orientationUpdateInterval = orientationUpdateInterval,
      coroutineScope = coroutineScope,
      sharingStarted = sharingStarted,
    )
  }
}

/**
 * Create and remember an [IosLocationProvider] that uses one [CLLocationManager] for both location
 * and orientation updates.
 */
@Composable
public fun rememberIosLocationAndOrientationProvider(
  minDistance: Length = 1.meters,
  desiredAccuracy: DesiredAccuracy = DesiredAccuracy.High,
  orientationUpdateInterval: Duration = 1.seconds,
  coroutineScope: CoroutineScope = rememberCoroutineScope(),
  sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
): IosLocationProvider {
  return rememberIosLocationProvider(
    minDistance = minDistance,
    desiredAccuracy = desiredAccuracy,
    enableLocation = true,
    enableOrientation = true,
    orientationUpdateInterval = orientationUpdateInterval,
    coroutineScope = coroutineScope,
    sharingStarted = sharingStarted,
  )
}
