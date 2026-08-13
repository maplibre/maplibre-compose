package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import org.maplibre.spatialk.units.extensions.inMeters
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLErrorDenied
import platform.CoreLocation.kCLErrorDomain
import platform.CoreLocation.kCLErrorLocationUnknown
import platform.CoreLocation.kCLErrorNetwork
import platform.CoreLocation.kCLErrorPromptDeclined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyBestForNavigation
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.CoreLocation.kCLLocationAccuracyReduced
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * A [LocationProvider] built on
 * [`CLLocationManager`](https://developer.apple.com/documentation/corelocation/cllocationmanager).
 *
 * Each collection creates a
 * [`CLLocationManager`](https://developer.apple.com/documentation/corelocation/cllocationmanager)
 * on the main dispatcher, applies the request's accuracy and distance preferences, and stops the
 * manager when collection ends.
 *
 * [LocationAccuracy.BestForNavigation] maps to
 * [`kCLLocationAccuracyBestForNavigation`](https://developer.apple.com/documentation/corelocation/kcllocationaccuracybestfornavigation),
 * [LocationAccuracy.High] maps to
 * [`kCLLocationAccuracyBest`](https://developer.apple.com/documentation/corelocation/kcllocationaccuracybest),
 * [LocationAccuracy.Balanced] maps to
 * [`kCLLocationAccuracyHundredMeters`](https://developer.apple.com/documentation/corelocation/kcllocationaccuracyhundredmeters),
 * [LocationAccuracy.Low] maps to
 * [`kCLLocationAccuracyKilometer`](https://developer.apple.com/documentation/corelocation/kcllocationaccuracykilometer),
 * and [LocationAccuracy.Lowest] maps to
 * [`kCLLocationAccuracyReduced`](https://developer.apple.com/documentation/corelocation/kcllocationaccuracyreduced).
 *
 * [`kCLErrorDenied`](https://developer.apple.com/documentation/corelocation/clerror/denied) maps to
 * [LocationUnavailableReason.ServicesDisabled] when location services are disabled and to
 * [LocationUnavailableReason.PermissionDenied] otherwise.
 * [`kCLErrorPromptDeclined`](https://developer.apple.com/documentation/corelocation/clerror/promptdeclined)
 * also maps to [LocationUnavailableReason.PermissionDenied].
 * [`kCLErrorLocationUnknown`](https://developer.apple.com/documentation/corelocation/clerror/locationunknown)
 * and [`kCLErrorNetwork`](https://developer.apple.com/documentation/corelocation/clerror/network)
 * map to [LocationUnavailableReason.TemporarilyUnavailable]. Other Core Location errors map to
 * [LocationUnavailableReason.UnexpectedFailure].
 */
public class IosLocationProvider : LocationProvider {
  override fun updates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
    if (!CLLocationManager.locationServicesEnabled()) {
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.ServicesDisabled))
      close()
      return@callbackFlow
    }

    val manager = CLLocationManager()
    val delegate = Delegate(channel)
    manager.delegate = delegate
    manager.desiredAccuracy =
      when (request.accuracy) {
        LocationAccuracy.BestForNavigation -> kCLLocationAccuracyBestForNavigation
        LocationAccuracy.High -> kCLLocationAccuracyBest
        LocationAccuracy.Balanced -> kCLLocationAccuracyHundredMeters
        LocationAccuracy.Low -> kCLLocationAccuracyKilometer
        LocationAccuracy.Lowest -> kCLLocationAccuracyReduced
      }
    manager.distanceFilter = request.minimumDistance.inMeters

    manager.location?.let(delegate::sendLocation)
    manager.startUpdatingLocation()

    // Retaining the delegate in this closure is required because CLLocationManager does not.
    awaitClose { delegate.stop(manager) }
  }
    .flowOn(Dispatchers.Main)

  private class Delegate(private val channel: SendChannel<LocationEvent>) :
    NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
      @Suppress("UNCHECKED_CAST") (didUpdateLocations as? List<CLLocation>)?.forEach(::sendLocation)
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
      channel.trySend(LocationEvent.Unavailable(didFailWithError.asUnavailableReason()))
    }

    fun sendLocation(location: CLLocation) {
      channel.trySend(LocationEvent.Fix(location.asMapLibreLocation()))
    }

    fun stop(manager: CLLocationManager) {
      manager.stopUpdatingLocation()
      manager.delegate = null
    }
  }
}

internal fun NSError.asUnavailableReason(
  locationServicesEnabled: Boolean = CLLocationManager.locationServicesEnabled()
): LocationUnavailableReason =
  when {
    domain != kCLErrorDomain -> LocationUnavailableReason.UnexpectedFailure
    code == kCLErrorDenied ->
      if (locationServicesEnabled) {
        LocationUnavailableReason.PermissionDenied
      } else {
        LocationUnavailableReason.ServicesDisabled
      }
    code == kCLErrorPromptDeclined -> LocationUnavailableReason.PermissionDenied
    code == kCLErrorLocationUnknown || code == kCLErrorNetwork ->
      LocationUnavailableReason.TemporarilyUnavailable
    else -> LocationUnavailableReason.UnexpectedFailure
  }

@Composable
public actual fun rememberDefaultLocationProvider(): LocationProvider =
  rememberIosLocationProvider()

/** Creates and remembers the default Core Location provider. */
@Composable
public fun rememberIosLocationProvider(): IosLocationProvider = remember {
  IosLocationProvider()
}
