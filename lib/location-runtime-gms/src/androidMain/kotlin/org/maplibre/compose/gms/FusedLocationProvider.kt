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
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.asMapLibreLocation
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
 * The [Context] constructor delegates [permission] and [requestPermission] to an
 * [AndroidLocationProvider]. The [FusedLocationProviderClient] constructor keeps the default
 * [LocationProvider.permission], which is always granted, and its [updates] still surface a
 * `SecurityException` as [LocationUnavailableReason.PermissionDenied].
 */
public class FusedLocationProvider
internal constructor(
  private val locationClient: FusedLocationProviderClient,
  private val permissionDelegate: LocationProvider?,
) : LocationProvider {

  /**
   * Creates a provider backed by [locationClient].
   *
   * Permission keeps the [LocationProvider.permission] default, which is always granted.
   */
  public constructor(locationClient: FusedLocationProviderClient) : this(locationClient, null)

  /**
   * Creates a provider with its own fused client and an [AndroidLocationProvider] as its permission
   * delegate.
   */
  public constructor(
    context: Context
  ) : this(
    LocationServices.getFusedLocationProviderClient(context),
    AndroidLocationProvider(context),
  )

  override val permission: StateFlow<LocationPermission>
    get() = permissionDelegate?.permission ?: super.permission

  override fun requestPermission() {
    if (permissionDelegate != null) {
      permissionDelegate.requestPermission()
    } else {
      super.requestPermission()
    }
  }

  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  override fun updates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
    val callback =
      object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
          result.locations.forEach { location ->
            trySend(LocationEvent.Fix(location.asMapLibreLocation()))
          }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
          if (!availability.isLocationAvailable) {
            trySend(LocationEvent.Unavailable(LocationUnavailableReason.TemporarilyUnavailable))
          }
        }
      }

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
            trySend(LocationEvent.Fix(location.asMapLibreLocation()))
          }

        locationClient
          .requestLocationUpdates(request.asGmsLocationRequest(), dispatcher.executor, callback)
          .await()
      } catch (error: SecurityException) {
        trySend(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied, error))
        close()
      }

      awaitClose()
    } finally {
      locationClient.removeLocationUpdates(callback)
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
