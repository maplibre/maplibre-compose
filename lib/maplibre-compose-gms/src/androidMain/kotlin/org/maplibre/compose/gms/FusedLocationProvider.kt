package org.maplibre.compose.gms

import android.Manifest
import android.content.Context
import android.location.Location as AndroidLocation
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.maplibre.compose.location.AndroidLocationPermissionController
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermissionController
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.asMapLibreLocation
import org.maplibre.compose.location.rememberAndroidLocationPermissionController
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.spatialk.units.extensions.inMeters

/**
 * A cold-session provider backed by Google Play Services fused location.
 *
 * Each collection requests fused updates, filters the initial cached location by
 * [LocationRequest.maximumInitialFixAge], and removes its callback when collection ends.
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
 * the flow and [rememberLocationState] reports them as
 * [LocationUnavailableReason.UnexpectedFailure].
 *
 * @param locationClient Google Play Services client used for cached and live locations.
 * @param permission Foreground permission state shared with callers.
 */
public class FusedLocationProvider
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
constructor(
  private val locationClient: FusedLocationProviderClient,
  override val permission: LocationPermissionController,
) : LocationProvider {
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
      locationClient
        .getLastLocation(
          LastLocationRequest.Builder()
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .build()
        )
        .await()
        ?.let { location ->
          val age = location.ageAtReceipt()
          val maximumAge = request.maximumInitialFixAge
          if (maximumAge == null || age <= maximumAge) {
            trySend(LocationEvent.Fix(location.asMapLibreLocation()))
          }
        }

      locationClient
        .requestLocationUpdates(request.asGmsLocationRequest(), dispatcher.executor, callback)
        .await()
    } catch (error: SecurityException) {
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied, error))
      close()
    }

    awaitClose { locationClient.removeLocationUpdates(callback) }
  }

  private companion object {
    private val dispatcher =
      Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "FusedLocationProvider").apply { isDaemon = true }
        }
        .asCoroutineDispatcher()
  }
}

/** Creates and remembers a fused provider from the current Android [context]. */
@Composable
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
public fun rememberFusedLocationProvider(
  context: Context = LocalContext.current,
  permission: AndroidLocationPermissionController = rememberAndroidLocationPermissionController(),
): FusedLocationProvider {
  val client = remember(context) { LocationServices.getFusedLocationProviderClient(context) }
  return rememberFusedLocationProvider(client, permission)
}

/** Creates and remembers a fused provider backed by [fusedLocationProviderClient]. */
@Composable
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
public fun rememberFusedLocationProvider(
  fusedLocationProviderClient: FusedLocationProviderClient,
  permission: AndroidLocationPermissionController = rememberAndroidLocationPermissionController(),
): FusedLocationProvider =
  remember(fusedLocationProviderClient, permission) {
    FusedLocationProvider(fusedLocationProviderClient, permission)
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

private fun AndroidLocation.ageAtReceipt(): Duration =
  (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos).coerceAtLeast(0).nanoseconds
