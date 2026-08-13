package org.maplibre.compose.location

import android.Manifest
import android.os.HandlerThread
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.engine.LocationEngineResult
import org.maplibre.spatialk.units.extensions.inMeters

/**
 * A [LocationProvider] based on a [LocationEngine] implementation.
 *
 * This implementation is provided only for backwards compatibility with existing [LocationEngine]
 * implementations in apps migrating to `maplibre-compose`. Always prefer using one of the other
 * provided [LocationProvider] implementations, or (re-)writing a custom [LocationProvider] from
 * scratch if possible. A custom provider should make collection own the platform request and its
 * cleanup.
 *
 * A `SecurityException` from
 * [`LocationEngineCallback.onFailure`](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.location.engine/-location-engine-callback/on-failure.html)
 * or request registration maps to [LocationUnavailableReason.PermissionDenied]. Other callback
 * failures map to [LocationUnavailableReason.UnexpectedFailure], because the compatibility API does
 * not classify their recoverability.
 *
 * @param locationEngine Existing MapLibre location engine to adapt.
 */
public class LocationEngineLocationProvider
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
constructor(private val locationEngine: LocationEngine) : LocationProvider {
  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  override fun updates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
    val liveCallback =
      object : LocationEngineCallback<LocationEngineResult> {
        override fun onSuccess(result: LocationEngineResult?) {
          result?.locations?.forEach { location ->
            trySend(LocationEvent.Fix(location.asMapLibreLocation()))
          }
        }

        override fun onFailure(exception: Exception) {
          trySend(
            LocationEvent.Unavailable(
              if (exception is SecurityException) {
                LocationUnavailableReason.PermissionDenied
              } else {
                LocationUnavailableReason.UnexpectedFailure
              },
              exception,
            )
          )
        }
      }

    val cachedCallback =
      object : LocationEngineCallback<LocationEngineResult> {
        override fun onSuccess(result: LocationEngineResult?) {
          result?.locations?.forEach { location ->
            trySend(LocationEvent.Fix(location.asMapLibreLocation()))
          }
        }

        override fun onFailure(exception: Exception) = Unit
      }

    try {
      locationEngine.getLastLocation(cachedCallback)
      locationEngine.requestLocationUpdates(
        request.asLocationEngineRequest(),
        liveCallback,
        handlerThread.looper,
      )
    } catch (error: SecurityException) {
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied, error))
      close()
    }
    awaitClose { locationEngine.removeLocationUpdates(liveCallback) }
  }

  private companion object {
    private val handlerThread by lazy {
      HandlerThread("LocationEngineLocationProvider").apply { start() }
    }
  }
}

private fun LocationRequest.asLocationEngineRequest(): LocationEngineRequest =
  LocationEngineRequest.Builder(minimumInterval.inWholeMilliseconds)
    .setFastestInterval(minimumInterval.inWholeMilliseconds)
    .setDisplacement(minimumDistance.inMeters.toFloat())
    .setPriority(
      when (accuracy) {
        LocationAccuracy.BestForNavigation,
        LocationAccuracy.High -> LocationEngineRequest.PRIORITY_HIGH_ACCURACY
        LocationAccuracy.Balanced -> LocationEngineRequest.PRIORITY_BALANCED_POWER_ACCURACY
        LocationAccuracy.Low -> LocationEngineRequest.PRIORITY_LOW_POWER
        LocationAccuracy.Lowest -> LocationEngineRequest.PRIORITY_NO_POWER
      }
    )
    .build()
