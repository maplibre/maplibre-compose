package org.maplibre.compose.gms

import android.Manifest
import android.content.Context
import androidx.annotation.MainThread
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
import java.util.concurrent.Executor
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
import org.maplibre.compose.location.asMapLibreLocationUpdate
import org.maplibre.spatialk.units.extensions.inMeters

/**
 * Foreground fused location from Google Play Services.
 *
 * Delivers the last known location when available, then applies the requested accuracy, interval,
 * and minimum distance. [LocationAccuracy.Lowest] receives passive updates.
 *
 * Unavailable locations report [LocationUnavailableReason.TemporarilyUnavailable]. Missing
 * permission reports [LocationUnavailableReason.PermissionDenied]. Other exceptions propagate to
 * the collector.
 *
 * The [Context] constructor handles permission requests. The [FusedLocationProviderClient]
 * constructor reports permission as granted and requires the caller to manage authorization.
 *
 * Create the provider, request permission, and close it on the main thread.
 */
public class FusedLocationProvider
internal constructor(
  private val locationClient: FusedLocationProviderClient,
  private val permissionDelegate: LocationProvider?,
  private val executor: Executor = dispatcher.executor,
) : LocationProvider {

  override val backendId: String = GmsLocationBackendId

  /**
   * Creates a provider backed by [locationClient].
   *
   * Permission keeps the [LocationProvider.permission] default, which is always granted.
   */
  @MainThread
  public constructor(locationClient: FusedLocationProviderClient) : this(locationClient, null)

  /**
   * Creates a provider with its own fused client and an [AndroidLocationProvider] as its permission
   * delegate.
   */
  @MainThread
  public constructor(
    context: Context
  ) : this(
    LocationServices.getFusedLocationProviderClient(context),
    AndroidLocationProvider(context),
  )

  override val permission: StateFlow<LocationPermission>
    get() = permissionDelegate?.permission ?: super.permission

  @MainThread
  override fun requestPermission() {
    if (permissionDelegate != null) {
      permissionDelegate.requestPermission()
    } else {
      super.requestPermission()
    }
  }

  @MainThread
  override fun close() {
    permissionDelegate?.close()
  }

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
            executor,
            callback,
          )
        registration.await()
      } catch (error: SecurityException) {
        trySend(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied, error))
        close()
      }

      awaitClose()
    } finally {
      registration?.addOnCompleteListener(executor) {
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
