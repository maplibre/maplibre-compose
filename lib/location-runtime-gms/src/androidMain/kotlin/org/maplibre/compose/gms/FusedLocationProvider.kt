package org.maplibre.compose.gms

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LastLocationRequest
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest as GmsLocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.maplibre.compose.location.AndroidLocationProvider
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationProviderAvailability
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationServicesStatus
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.asMapLibreLocationUpdate
import org.maplibre.spatialk.units.extensions.inMeters

/**
 * A location provider backed by Google Play Services fused location.
 *
 * Each collection requests fused updates, emits the last location when one exists, and removes its
 * callback when collection ends.
 *
 * [LocationAccuracy.BestForNavigation] and [LocationAccuracy.High] map to
 * [`Priority.PRIORITY_HIGH_ACCURACY`](https://developers.google.com/android/reference/com/google/android/gms/location/Priority#PRIORITY_HIGH_ACCURACY),
 * [LocationAccuracy.Balanced] maps to
 * [`Priority.PRIORITY_BALANCED_POWER_ACCURACY`](https://developers.google.com/android/reference/com/google/android/gms/location/Priority#PRIORITY_BALANCED_POWER_ACCURACY),
 * [LocationAccuracy.Low] maps to
 * [`Priority.PRIORITY_LOW_POWER`](https://developers.google.com/android/reference/com/google/android/gms/location/Priority#PRIORITY_LOW_POWER),
 * and [LocationAccuracy.Lowest] maps to
 * [`Priority.PRIORITY_PASSIVE`](https://developers.google.com/android/reference/com/google/android/gms/location/Priority#PRIORITY_PASSIVE).
 *
 * [`LocationAvailability.isLocationAvailable`](https://developers.google.com/android/reference/com/google/android/gms/location/LocationAvailability#isLocationAvailable())
 * equal to `false` maps to [LocationUnavailableReason.TemporarilyUnavailable]. A
 * `SecurityException` maps to [LocationUnavailableReason.PermissionDenied]. Other exceptions escape
 * the flow, and the collector classifies them as [LocationUnavailableReason.UnexpectedFailure].
 *
 * [availability], [permission], [locationServices], and [requestPermission] delegate to an
 * [AndroidLocationProvider].
 */
public class FusedLocationProvider
internal constructor(
  private val locationClient: FusedLocationProviderClient,
  private val platformDelegate: LocationProvider,
) : LocationProvider {

  override val backendId: String = GmsLocationBackendId

  /** Creates a provider with an [AndroidLocationProvider] for platform state and permission. */
  public constructor(
    context: Context
  ) : this(
    LocationServices.getFusedLocationProviderClient(context),
    AndroidLocationProvider(context),
  )

  override val availability: LocationProviderAvailability
    get() = platformDelegate.availability

  override val permission: StateFlow<LocationPermission>
    get() = platformDelegate.permission

  override val locationServices: StateFlow<LocationServicesStatus>
    get() = platformDelegate.locationServices

  override fun requestPermission(): Unit = platformDelegate.requestPermission()

  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  override fun updates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
    val callback =
      object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
          result.locations.forEach { location ->
            trySend(location.asMapLibreLocationUpdate())
          }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
          if (!availability.isLocationAvailable) {
            trySend(LocationEvent.Unavailable(LocationUnavailableReason.TemporarilyUnavailable))
          }
        }
      }

    var registration: Task<Void>? = null
    try {
      try {
        locationClient
          .getLastLocation(
            LastLocationRequest.Builder()
              .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
              .build()
          )
          .await()
          ?.let { location ->
            trySend(location.asMapLibreLocationUpdate())
          }

        registration =
          locationClient.requestLocationUpdates(
            request.asGmsLocationRequest(),
            dispatcher.executor,
            callback,
          )
        registration.await()
      } catch (error: SecurityException) {
        trySend(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied, error))
        close()
      }

      awaitClose()
    } finally {
      registration?.addOnCompleteListener(dispatcher.executor) {
        locationClient.removeLocationUpdates(callback)
      }
    }
  }

  private companion object {
    private val dispatcher =
      Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "FusedLocationProvider").apply { isDaemon = true }
        }
        .asCoroutineDispatcher()
  }
}

private fun LocationRequest.asGmsLocationRequest(): GmsLocationRequest =
  GmsLocationRequest.Builder(
      when (accuracy) {
        LocationAccuracy.BestForNavigation,
        LocationAccuracy.High -> Priority.PRIORITY_HIGH_ACCURACY
        LocationAccuracy.Balanced -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        LocationAccuracy.Low -> Priority.PRIORITY_LOW_POWER
        LocationAccuracy.Lowest -> Priority.PRIORITY_PASSIVE
      },
      minimumInterval.inWholeMilliseconds,
    )
    .setMinUpdateIntervalMillis(minimumInterval.inWholeMilliseconds)
    .setMinUpdateDistanceMeters(minimumDistance.inMeters.toFloat())
    .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
    .build()
