package org.maplibre.compose.location

import android.Manifest
import android.os.HandlerThread
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineDefault
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.engine.LocationEngineResult

public class LocationEngineGeoLocator
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
constructor(
  private val locationEngine: LocationEngine,
  private val locationEngineRequest: LocationEngineRequest,
  coroutineScope: CoroutineScope,
) : GeoLocator, LocationEngineCallback<LocationEngineResult>, RememberObserver {
  private val _location = MutableStateFlow<Location?>(null)
  override val location: StateFlow<Location?> = _location.asStateFlow()

  init {
    locationEngine.getLastLocation(this)

    if (!handlerThread.isAlive) {
      handlerThread.start()
    }

    coroutineScope.launch {
      _location.subscriptionCount
        .distinctUntilChangedBy { it > 0 }
        .collect { subscriptionCount ->
          if (subscriptionCount > 0) {
            locationEngine.requestLocationUpdates(
              locationEngineRequest,
              this@LocationEngineGeoLocator,
              handlerThread.looper,
            )
          } else {
            removeListener()
          }
        }
    }
  }

  @OptIn(ExperimentalTime::class)
  override fun onSuccess(result: LocationEngineResult?) {
    result?.locations?.forEach { _location.value = it.asMapLibreLocation() }
  }

  override fun onFailure(exception: Exception) {}

  override fun onRemembered() {}

  override fun onAbandoned() {
    removeListener()
  }

  override fun onForgotten() {
    removeListener()
  }

  private fun removeListener() {
    locationEngine.removeLocationUpdates(this)
  }

  private companion object {
    private val handlerThread by lazy { HandlerThread("LocationEngineGeoLocator") }
  }
}

@Composable
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
public fun rememberAndroidGeoLocator(
  locationEngineRequest: LocationEngineRequest = defaultLocationEngineRequest
): GeoLocator {
  val context by rememberUpdatedState(LocalContext.current)
  val locationEngine = remember(context) { LocationEngineDefault.getDefaultLocationEngine(context) }
  return rememberAndroidGeoLocator(locationEngine, locationEngineRequest)
}

@Composable
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
public fun rememberAndroidGeoLocator(
  locationEngine: LocationEngine,
  locationEngineRequest: LocationEngineRequest = defaultLocationEngineRequest,
): GeoLocator {
  val coroutineScope = rememberCoroutineScope()
  return remember(locationEngine) {
    LocationEngineGeoLocator(locationEngine, locationEngineRequest, coroutineScope)
  }
}

private val defaultLocationEngineRequest =
  LocationEngineRequest.Builder(1000)
    .setFastestInterval(1000)
    .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
    .build()
