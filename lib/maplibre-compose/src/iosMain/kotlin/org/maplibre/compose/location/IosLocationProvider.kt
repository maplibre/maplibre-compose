package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
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
import platform.darwin.NSObject

/**
 * A [LocationProvider] built on the [CLLocationManager] platform APIs.
 *
 * @param minDistanceMeters the minimum distance between location updates
 * @param desiredAccuracy the [DesiredAccuracy] for location updates.
 * @param coroutineScope the [CoroutineScope] used to share the [location] flow
 * @param sharingStarted parameter for [stateIn] call of [location]
 */
public class IosLocationProvider(
  private val minDistanceMeters: Double,
  private val desiredAccuracy: DesiredAccuracy,
  coroutineScope: CoroutineScope,
  sharingStarted: SharingStarted,
) : LocationProvider {
  private val locationManager = CLLocationManager()

  init {
    if (
      locationManager.authorizationStatus != kCLAuthorizationStatusAuthorizedAlways &&
        locationManager.authorizationStatus != kCLAuthorizationStatusAuthorizedWhenInUse
    ) {
      throw PermissionException()
    }
  }

  override val location: StateFlow<Location?> =
    callbackFlow {
        locationManager.delegate = Delegate(channel)

        locationManager.desiredAccuracy =
          when (desiredAccuracy) {
            DesiredAccuracy.Highest -> kCLLocationAccuracyBestForNavigation
            DesiredAccuracy.High -> kCLLocationAccuracyBest
            DesiredAccuracy.Balanced -> kCLLocationAccuracyHundredMeters
            DesiredAccuracy.Low -> kCLLocationAccuracyKilometer
            DesiredAccuracy.Lowest -> kCLLocationAccuracyReduced
          }
        locationManager.distanceFilter = minDistanceMeters

        locationManager.stopUpdatingLocation()
        locationManager.startUpdatingLocation()

        awaitClose { locationManager.stopUpdatingLocation() }
      }
      .stateIn(coroutineScope, sharingStarted, null)

  private inner class Delegate(private val channel: SendChannel<Location?>) :
    NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
      @Suppress("UNCHECKED_CAST") val locations = didUpdateLocations as? List<CLLocation>

      locations?.forEach { channel.trySendBlocking(it.asMapLibreLocation()).getOrThrow() }
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {}
  }
}

@Composable
public actual fun rememberDefaultLocationProvider(
  updateInterval: Duration,
  desiredAccuracy: DesiredAccuracy,
  minDistanceMeters: Double,
): LocationProvider {
  return rememberIosLocationProvider(minDistanceMeters, desiredAccuracy)
}

@Composable
public fun rememberIosLocationProvider(
  minDistanceMeters: Double = 1.0,
  desiredAccuracy: DesiredAccuracy = DesiredAccuracy.High,
  coroutineScope: CoroutineScope = rememberCoroutineScope(),
  sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
): IosLocationProvider {
  return remember(minDistanceMeters, desiredAccuracy, coroutineScope, sharingStarted) {
    IosLocationProvider(
      minDistanceMeters = minDistanceMeters,
      desiredAccuracy = desiredAccuracy,
      coroutineScope = coroutineScope,
      sharingStarted = sharingStarted,
    )
  }
}
