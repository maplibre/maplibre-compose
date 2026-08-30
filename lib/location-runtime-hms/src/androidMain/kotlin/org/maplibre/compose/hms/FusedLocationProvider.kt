package org.maplibre.compose.hms

import android.Manifest
import android.content.Context
import android.os.HandlerThread
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
import org.maplibre.compose.location.LocationProviderAvailability
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationServicesStatus
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.asMapLibreLocationUpdate
import org.maplibre.spatialk.units.extensions.inMeters

/**
 * A location provider backed by Huawei Mobile Services fused location.
 *
 * Each collection requests fused updates, emits the last location when one exists, and removes its
 * callback when collection ends. Every update request explicitly selects
 * [`LocationRequest.COORDINATE_TYPE_WGS84`](https://developer.huawei.com/consumer/en/doc/HMSCore-References/locationrequest-0000001050986193),
 * which is the coordinate system that MapLibre expects.
 *
 * [LocationAccuracy.BestForNavigation] and [LocationAccuracy.High] map to
 * `LocationRequest.PRIORITY_HIGH_ACCURACY`, [LocationAccuracy.Balanced] maps to
 * `LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY`, [LocationAccuracy.Low] maps to
 * `LocationRequest.PRIORITY_LOW_POWER`, and [LocationAccuracy.Lowest] maps to
 * `LocationRequest.PRIORITY_NO_POWER`.
 *
 * [LocationAvailability.isLocationAvailable] equal to `false` maps to
 * [LocationUnavailableReason.TemporarilyUnavailable]. A `SecurityException` maps to
 * [LocationUnavailableReason.PermissionDenied]. Other exceptions escape the flow, and the collector
 * classifies them as [LocationUnavailableReason.UnexpectedFailure].
 *
 * [availability], [permission], [locationServices], and [requestPermission] delegate to an
 * [AndroidLocationProvider].
 */
public class FusedLocationProvider
internal constructor(
  private val locationClient: FusedLocationProviderClient,
  private val platformDelegate: LocationProvider,
) : LocationProvider {

  override val backendId: String = HmsLocationBackendId

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
