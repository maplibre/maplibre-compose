package org.maplibre.compose.location

import android.Manifest
import android.os.HandlerThread
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.engine.LocationEngineResult

/**
 * A [LocationProvider] based on a [LocationEngine] implementation and a provided
 * [LocationEngineRequest].
 *
 * This implementation is provided only for backwards compatibility with existing [LocationEngine]
 * implementations in apps migrating to `maplibre-compose`. Always prefer using one of the other
 * provided [LocationProvider] implementations, or (re-)writing a custom [LocationProvider] from
 * scratch if possible.
 *
 * @param locationEngine the [LocationEngine] to use
 * @param locationEngineRequest the [LocationEngineRequest] to use
 * @param coroutineScope the [CoroutineScope] used to share the [location] flow
 * @param sharingStarted parameter for [stateIn] call of [location]
 */
public class LocationEngineLocationProvider
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
constructor(
  private val locationEngine: LocationEngine,
  private val locationEngineRequest: LocationEngineRequest = defaultLocationEngineRequest,
  coroutineScope: CoroutineScope,
  sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
) : LocationProvider {
  override val location: StateFlow<Location?>

  init {
    if (!handlerThread.isAlive) {
      handlerThread.start()
    }

    location =
      callbackFlow {
          val callback =
            object : LocationEngineCallback<LocationEngineResult> {
              override fun onSuccess(result: LocationEngineResult?) {
                result?.locations?.forEach { trySendBlocking(it.asMapLibreLocation()).getOrThrow() }
              }

              override fun onFailure(exception: Exception) {}
            }

          locationEngine.getLastLocation(callback)

          locationEngine.requestLocationUpdates(
            locationEngineRequest,
            callback,
            handlerThread.looper,
          )

          awaitClose { locationEngine.removeLocationUpdates(callback) }
        }
        .stateIn(coroutineScope, sharingStarted, null)
  }

  private companion object {
    private val handlerThread by lazy { HandlerThread("LocationEngineLocationProvider") }
  }
}

private val defaultLocationEngineRequest =
  LocationEngineRequest.Builder(1000)
    .setFastestInterval(1000)
    .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
    .build()
