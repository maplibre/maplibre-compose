package org.maplibre.compose.gms

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.location.DeviceOrientation
import com.google.android.gms.location.DeviceOrientationRequest
import com.google.android.gms.location.FusedOrientationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import org.maplibre.compose.location.BearingMeasurement
import org.maplibre.compose.location.Orientation
import org.maplibre.compose.location.OrientationProvider
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees

/**
 * A [OrientationProvider] based on a [LocationRequest] for [FusedOrientationProviderClient]
 *
 * @param orientationClient the [FusedOrientationProviderClient] to use
 * @param locationRequest the [LocationRequest] to use
 * @param coroutineScope the [CoroutineScope] used to share the [orientation] flow
 * @param sharingStarted parameter for [stateIn] call of [orientation]
 */
public class FusedOrientationProvider(
  private val orientationClient: FusedOrientationProviderClient,
  private val locationRequest: LocationRequest,
  coroutineScope: CoroutineScope,
  sharingStarted: SharingStarted,
) : OrientationProvider {
  override val orientation: StateFlow<Orientation?> =
    callbackFlow {
        val request =
          DeviceOrientationRequest.Builder(
              locationRequest.intervalMillis.milliseconds.inWholeMicroseconds
            )
            .build()
        val callback: (DeviceOrientation) -> Unit = { orientation ->
          trySendBlocking(
              Orientation(
                orientation =
                  BearingMeasurement(
                    bearing = Bearing.North - orientation.headingDegrees.toDouble().degrees,
                    accuracy = orientation.headingErrorDegrees.toDouble().degrees,
                  ),
                timestamp = TimeSource.Monotonic.markNow(),
              )
            )
            .getOrThrow()
        }

        orientationClient.requestOrientationUpdates(request, dispatcher.executor, callback)

        awaitClose { orientationClient.removeOrientationUpdates(callback) }
      }
      .stateIn(coroutineScope, sharingStarted, null)

  private companion object {
    private val dispatcher =
      Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "FusedOrientationProvider").apply { isDaemon = true }
        }
        .asCoroutineDispatcher()
  }
}

/** Create and remember a [FusedOrientationProvider] with the provided [locationRequest] */
@Composable
public fun rememberFusedOrientationProvider(
  locationRequest: LocationRequest = defaultLocationRequest,
  context: Context = LocalContext.current,
): FusedOrientationProvider {
  val orientationClient =
    remember(context) { LocationServices.getFusedOrientationProviderClient(context) }
  return rememberFusedOrientationProvider(orientationClient, locationRequest)
}

/**
 * Create and remember a [FusedOrientationProvider] with the provided [locationRequest] and
 * [FusedOrientationProviderClient]
 */
@Composable
public fun rememberFusedOrientationProvider(
  fusedOrientationProviderClient: FusedOrientationProviderClient,
  locationRequest: LocationRequest = defaultLocationRequest,
  coroutineScope: CoroutineScope = rememberCoroutineScope(),
  sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
): FusedOrientationProvider {
  return remember(fusedOrientationProviderClient) {
    FusedOrientationProvider(
      orientationClient = fusedOrientationProviderClient,
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
