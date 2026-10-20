package org.maplibre.compose.hms

import android.content.Context
import android.os.HandlerThread
import androidx.annotation.MainThread
import com.huawei.hmf.tasks.Task
import com.huawei.hmf.tasks.TaskExecutors
import com.huawei.hms.location.FusedLocationProviderClient
import com.huawei.hms.location.LocationAvailability
import com.huawei.hms.location.LocationCallback
import com.huawei.hms.location.LocationRequest as HmsLocationRequest
import com.huawei.hms.location.LocationResult
import com.huawei.hms.location.LocationServices
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.retryWhen
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationBackend
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.asMaplibreLocationUpdate
import org.maplibre.compose.location.createAndroidLocationProvider
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
 * The [Context] overload handles permission requests. The [FusedLocationProviderClient] overload
 * reports permission as granted and requires the caller to manage authorization.
 *
 * Create the provider, request permission, and close it on the main thread.
 */
@MainThread
public fun createFusedLocationProvider(context: Context): LocationProvider =
  LocationProvider(FusedLocationBackend(context))

/** Creates a fused provider whose caller manages permission. */
@MainThread
public fun createFusedLocationProvider(
  locationClient: FusedLocationProviderClient,
): LocationProvider = LocationProvider(FusedLocationBackend(locationClient))

internal class FusedLocationBackend
internal constructor(
  private val locationClient: FusedLocationProviderClient,
  private val permissionDelegate: LocationProvider?,
) : LocationBackend {

  override val id: String = HmsLocationBackendId

  /**
   * Creates a provider backed by [locationClient].
   *
   * Permission keeps the [LocationBackend.permission] default, which is always granted.
   */
  @MainThread constructor(locationClient: FusedLocationProviderClient) : this(locationClient, null)

  /**
   * Creates a backend with its own fused client and a framework provider as its permission
   * delegate.
   */
  @MainThread
  constructor(
    context: Context,
  ) : this(
    LocationServices.getFusedLocationProviderClient(context),
    createAndroidLocationProvider(context),
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

  @OptIn(ExperimentalCoroutinesApi::class)
  override fun updates(request: LocationRequest): Flow<LocationEvent> =
    permission.flatMapLatest { status ->
      if (status is LocationPermission.Granted) {
        locationUpdates(request).retryWhen { error, _ ->
          if (error !is SecurityException) return@retryWhen false
          emit(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied, error))
          delay(1.seconds)
          true
        }
      } else {
        flowOf(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied))
      }
    }

  @Suppress("MissingPermission")
  private fun locationUpdates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
    val callback =
      object : LocationCallback() {
        override fun onLocationResult(result: LocationResult?) {
          result?.locations?.forEach { location ->
            trySend(location.asMaplibreLocationUpdate())
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
            location?.let { trySend(it.asMaplibreLocationUpdate()) }
          }
          .addOnFailureListener { error -> close(error) }

        locationClient
          .requestLocationUpdates(request.asHmsLocationRequest(), callback, callbackThread.looper)
          .addOnFailureListener { error -> close(error) }
      } catch (error: SecurityException) {
        close(error)
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

  private companion object {
    private val callbackThread by lazy {
      HandlerThread("HmsFusedLocationProvider").apply { start() }
    }
  }
}

private fun Task<*>.invokeOnCompletion(block: () -> Unit) {
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
      },
    )
    .setInterval(minimumInterval.inWholeMilliseconds)
    .setFastestInterval(minimumInterval.inWholeMilliseconds)
    .setSmallestDisplacement(minimumDistance.inMeters.toFloat())
    .setCoordinateType(HmsLocationRequest.COORDINATE_TYPE_WGS84)
