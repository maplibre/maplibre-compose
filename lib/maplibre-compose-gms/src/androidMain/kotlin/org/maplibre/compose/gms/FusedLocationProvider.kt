package org.maplibre.compose.gms

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.tasks.await
import org.maplibre.compose.location.Location
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.asMapLibreLocation

/**
 * A [LocationProvider] based on a [LocationRequest] for [FusedLocationProviderClient]
 *
 * @param locationClient the [FusedLocationProviderClient] to use
 * @param locationRequest the [LocationRequest] to use
 * @param coroutineScope the [CoroutineScope] used to share the [location] flow
 * @param sharingStarted parameter for [stateIn] call of [location]
 */
public class FusedLocationProvider
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
constructor(
  private val locationClient: FusedLocationProviderClient,
  private val locationRequest: LocationRequest,
  coroutineScope: CoroutineScope,
  sharingStarted: SharingStarted,
) : LocationProvider {
  @Suppress("JoinDeclarationAndAssignment") // because of @RequiresPermission
  override val location: StateFlow<Location?>

  init {
    location =
      callbackFlow {
          val callback =
            object : LocationCallback() {
              override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { trySendBlocking(it.asMapLibreLocation()).getOrThrow() }
              }

              override fun onLocationAvailability(availability: LocationAvailability) {}
            }

          val lastLocation =
            locationClient
              .getLastLocation(
                LastLocationRequest.Builder()
                  .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                  .build()
              )
              .await()
              ?.asMapLibreLocation()
          send(lastLocation)

          locationClient
            .requestLocationUpdates(locationRequest, dispatcher.executor, callback)
            .await()

          awaitClose { locationClient.removeLocationUpdates(callback) }
        }
        .stateIn(coroutineScope, sharingStarted, null)
  }

  private companion object {
    private val dispatcher =
      Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "FusedLocationProvider").apply { isDaemon = true }
        }
        .asCoroutineDispatcher()
  }
}

/** Create and remember a [FusedLocationProvider] with the provided [locationRequest] */
@Composable
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
public fun rememberFusedLocationProvider(
  locationRequest: LocationRequest = defaultLocationRequest,
  context: Context = LocalContext.current,
): FusedLocationProvider {
  val locationClient =
    remember(context) { LocationServices.getFusedLocationProviderClient(context) }
  return rememberFusedLocationProvider(locationClient, locationRequest)
}

/**
 * Create and remember a [FusedLocationProvider] with the provided [locationRequest] and
 * [fusedLocationProviderClient]
 */
@Composable
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
public fun rememberFusedLocationProvider(
  fusedLocationProviderClient: FusedLocationProviderClient,
  locationRequest: LocationRequest = defaultLocationRequest,
  coroutineScope: CoroutineScope = rememberCoroutineScope(),
  sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
): FusedLocationProvider {
  return remember(fusedLocationProviderClient) {
    FusedLocationProvider(
      locationClient = fusedLocationProviderClient,
      locationRequest = locationRequest,
      coroutineScope = coroutineScope,
      sharingStarted = sharingStarted,
    )
  }
}

private val defaultLocationRequest =
  LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
    .setMinUpdateIntervalMillis(1000)
    .build()
