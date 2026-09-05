package org.maplibre.compose.hms

import android.Manifest
import android.content.Context
import android.os.HandlerThread
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import com.huawei.hmf.tasks.Task
import com.huawei.hmf.tasks.TaskExecutors
import com.huawei.hms.location.FusedLocationProviderClient
import com.huawei.hms.location.LocationAvailability
import com.huawei.hms.location.LocationCallback
import com.huawei.hms.location.LocationRequest as HmsLocationRequest
import com.huawei.hms.location.LocationResult
import com.huawei.hms.location.LocationServices
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
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
 * Foreground fused location from Huawei Mobile Services.
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
) : LocationProvider {

  override val backendId: String = HmsLocationBackendId

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
        override fun onLocationResult(result: LocationResult?) {
          result?.locations?.forEach { location ->
            trySend(location.asMapLibreLocationUpdate())
          }
        }

        override fun onLocationAvailability(availability: LocationAvailability?) {
          if (availability?.isLocationAvailable == false) {
            trySend(LocationEvent.Unavailable(LocationUnavailableReason.TemporarilyUnavailable))
          }
        }
      }

    val registrationTask =
      try {
        locationClient.lastLocation
          .addOnSuccessListener { location ->
            location?.let { trySend(it.asMapLibreLocationUpdate()) }
          }
          .addOnFailureListener { error -> handleFailure(error) }

        locationClient
          .requestLocationUpdates(request.asHmsLocationRequest(), callback, callbackThread.looper)
          .addOnFailureListener { error -> handleFailure(error) }
      } catch (error: SecurityException) {
        handleFailure(error)
        null
      }

    awaitClose {
      if (registrationTask == null) {
        locationClient.removeLocationUpdates(callback)
      } else {
        registrationTask.invokeOnCompletion { locationClient.removeLocationUpdates(callback) }
      }
    }
  }

  private fun ProducerScope<LocationEvent>.handleFailure(error: Exception) {
    if (error is SecurityException) {
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied, error))
      close()
    } else {
      close(error)
    }
  }

  private companion object {
    private val callbackThread by lazy {
      HandlerThread("HmsFusedLocationProvider").apply { start() }
    }
  }
}

internal fun Task<*>.invokeOnCompletion(block: () -> Unit) {
  addOnCompleteListener(TaskExecutors.immediate()) { block() }
}

internal fun LocationRequest.asHmsLocationRequest(): HmsLocationRequest =
  HmsLocationRequest.create()
    .setPriority(
      when (accuracy) {
        LocationAccuracy.BestForNavigation,
        LocationAccuracy.High -> HmsLocationRequest.PRIORITY_HIGH_ACCURACY
        LocationAccuracy.Balanced -> HmsLocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        LocationAccuracy.Low -> HmsLocationRequest.PRIORITY_LOW_POWER
        LocationAccuracy.Lowest -> HmsLocationRequest.PRIORITY_NO_POWER
      }
    )
    .setInterval(minimumInterval.inWholeMilliseconds)
    .setFastestInterval(minimumInterval.inWholeMilliseconds)
    .setSmallestDisplacement(minimumDistance.inMeters.toFloat())
    .setCoordinateType(HmsLocationRequest.COORDINATE_TYPE_WGS84)
