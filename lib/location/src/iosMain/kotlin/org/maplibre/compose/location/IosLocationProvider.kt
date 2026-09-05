package org.maplibre.compose.location

import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
 * Foreground location from Core Location.
 *
 * Applies the requested accuracy and minimum distance. [LocationRequest.minimumInterval] is
 * ignored. See [IosLocationPermissionRequester] for permission behavior.
 *
 * Create the provider, request permission, and close it on the main thread.
 *
 * Disabled location services report [LocationUnavailableReason.ServicesDisabled]. Denied or
 * declined permission reports [LocationUnavailableReason.PermissionDenied]. Network failures and
 * unknown locations report [LocationUnavailableReason.TemporarilyUnavailable]. Other failures
 * report [LocationUnavailableReason.UnexpectedFailure].
 */
public class IosLocationProvider
internal constructor(private val requester: IosLocationPermissionRequester) : LocationProvider {
  /** Creates a provider with its own permission requester. */
  public constructor() : this(IosLocationPermissionRequester())

  override val permission: StateFlow<LocationPermission>
    get() = requester.status

  override fun requestPermission(): Unit = requester.requestForegroundPermission()

  override fun close(): Unit = requester.close()

  override fun updates(request: LocationRequest): Flow<LocationEvent> =
    callbackFlow<IosLocationCallback> {
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
        manager.startUpdatingLocation()

        // Retaining the delegate in this closure is required because CLLocationManager does not.
        awaitClose { delegate.stop(manager) }
      }
      .flowOn(Dispatchers.Main)
      .map { callback ->
        when (callback) {
          is IosLocationCallback.Update -> callback.event
          is IosLocationCallback.Failure ->
            LocationEvent.Unavailable(callback.error.asUnavailableReason())
        }
      }

  private class Delegate(private val channel: SendChannel<IosLocationCallback>) :
    NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
      @Suppress("UNCHECKED_CAST") (didUpdateLocations as? List<CLLocation>)?.forEach(::sendLocation)
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
      channel.trySend(IosLocationCallback.Failure(didFailWithError))
    }

    fun sendLocation(location: CLLocation) {
      channel.trySend(
        IosLocationCallback.Update(
          LocationEvent.Update(
            location.asMapLibreLocationMeasurement(),
            TimeSource.Monotonic.markNow() - location.ageAtReceipt(),
          )
        )
      )
    }

    fun stop(manager: CLLocationManager) {
      manager.stopUpdatingLocation()
      manager.delegate = null
    }
  }
}

private suspend fun locationServicesEnabled(): Boolean = readLocationServicesEnabled {
  CLLocationManager.locationServicesEnabled()
}

internal suspend fun readLocationServicesEnabled(read: () -> Boolean): Boolean =
  withContext(Dispatchers.Default) { read() }

private sealed interface IosLocationCallback {
  data class Update(val event: LocationEvent.Update) : IosLocationCallback

  data class Failure(val error: NSError) : IosLocationCallback
}

internal suspend fun NSError.asUnavailableReason(
  locationServicesEnabled: suspend () -> Boolean = ::locationServicesEnabled
): LocationUnavailableReason =
  when {
    domain != kCLErrorDomain -> LocationUnavailableReason.UnexpectedFailure
    code == kCLErrorDenied ->
      if (locationServicesEnabled()) {
        LocationUnavailableReason.PermissionDenied
      } else {
        LocationUnavailableReason.ServicesDisabled
      }
    code == kCLErrorPromptDeclined -> LocationUnavailableReason.PermissionDenied
    code == kCLErrorLocationUnknown || code == kCLErrorNetwork ->
      LocationUnavailableReason.TemporarilyUnavailable
    else -> LocationUnavailableReason.UnexpectedFailure
  }
