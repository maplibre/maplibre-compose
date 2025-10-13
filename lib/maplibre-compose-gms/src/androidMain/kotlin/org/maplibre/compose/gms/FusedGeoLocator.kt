package org.maplibre.compose.gms

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LastLocationRequest
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.maplibre.compose.location.GeoLocator
import org.maplibre.compose.location.Location
import org.maplibre.compose.location.asMapLibreLocation

/** A [GeoLocator] based on a [LocationRequest] for [FusedLocationProviderClient] */
public class FusedGeoLocator
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
constructor(
  private val locationClient: FusedLocationProviderClient,
  private val locationRequest: LocationRequest,
  coroutineScope: CoroutineScope,
) : GeoLocator, RememberObserver {
  private val _location = MutableStateFlow<Location?>(null)
  override val location: StateFlow<Location?> = _location.asStateFlow()
  private val callback =
    object : LocationCallback() {
      override fun onLocationResult(result: LocationResult) {
        result.locations.forEach { _location.value = it.asMapLibreLocation() }
      }

      override fun onLocationAvailability(availability: LocationAvailability) {}
    }

  init {
    coroutineScope.launch {
      _location.subscriptionCount
        .distinctUntilChangedBy { it > 0 }
        .collect { subscriptionCount ->
          if (subscriptionCount > 0) {
            _location.value =
              locationClient
                .getLastLocation(
                  LastLocationRequest.Builder()
                    .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                    .build()
                )
                .await()
                ?.asMapLibreLocation()

            locationClient.requestLocationUpdates(locationRequest, executor, callback).await()
          } else {
            removeListener()
          }
        }
    }
  }

  override fun onRemembered() {}

  override fun onAbandoned() {
    removeListener()
  }

  override fun onForgotten() {
    removeListener()
  }

  private fun removeListener() {
    locationClient.removeLocationUpdates(callback)
  }

  private companion object {
    private val executor by lazy {
      Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FusedGeoLocator").apply { isDaemon = true }
      }
    }
  }
}

/**
 * Create and remember a [FusedGeoLocator] with the provided [locationRequest]
 *
 * The [minimum update interval][LocationRequest.getMinUpdateIntervalMillis] will be coerced to be
 * at least 1 second.
 */
@Composable
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
public fun rememberFusedGeoLocator(
  context: Context = LocalContext.current,
  locationRequest: LocationRequest = defaultLocationRequest,
): FusedGeoLocator {
  val locationClient =
    remember(context) { LocationServices.getFusedLocationProviderClient(context) }
  return rememberFusedGeoLocator(locationClient, locationRequest)
}

/**
 * Create and remember a [FusedGeoLocator] with the provided [locationRequest] and
 * [fusedLocationProviderClient]
 *
 * The [minimum update interval][LocationRequest.getMinUpdateIntervalMillis] will be coerced to be
 * at least 1 second.
 */
@Composable
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
public fun rememberFusedGeoLocator(
  fusedLocationProviderClient: FusedLocationProviderClient,
  locationRequest: LocationRequest = defaultLocationRequest,
): FusedGeoLocator {
  val coroutineScope = rememberCoroutineScope()
  return remember(fusedLocationProviderClient) {
    FusedGeoLocator(
      locationClient = fusedLocationProviderClient,
      locationRequest =
        LocationRequest.Builder(locationRequest)
          .setMinUpdateIntervalMillis(locationRequest.minUpdateIntervalMillis.coerceAtLeast(1000))
          .build(),
      coroutineScope = coroutineScope,
    )
  }
}

private val defaultLocationRequest =
  LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
    .setMinUpdateIntervalMillis(1000)
    .build()
