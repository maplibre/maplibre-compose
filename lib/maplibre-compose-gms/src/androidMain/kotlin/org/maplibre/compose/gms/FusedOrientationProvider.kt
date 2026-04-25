package org.maplibre.compose.gms

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.location.DeviceOrientation
import com.google.android.gms.location.DeviceOrientationRequest
import com.google.android.gms.location.FusedOrientationProviderClient
import com.google.android.gms.location.LocationServices
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import org.maplibre.compose.location.BearingWithAccuracy
import org.maplibre.compose.location.Orientation
import org.maplibre.compose.location.OrientationProvider
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees

/**
 * A [OrientationProvider] based on a [DeviceOrientationRequest] for
 * [FusedOrientationProviderClient]
 *
 * @param orientationClient the [FusedOrientationProviderClient] to use
 * @param deviceOrientationRequest the [DeviceOrientationRequest] to use
 * @param coroutineScope the [CoroutineScope] used to share the [orientation] flow
 * @param sharingStarted parameter for [stateIn] call of [orientation]
 */
public class FusedOrientationProvider(
  private val orientationClient: FusedOrientationProviderClient,
  private val deviceOrientationRequest: DeviceOrientationRequest,
  coroutineScope: CoroutineScope,
  sharingStarted: SharingStarted,
) : OrientationProvider {
  @OptIn(FlowPreview::class)
  override val orientation: StateFlow<Orientation?> =
    callbackFlow {
        val callback: (DeviceOrientation) -> Unit = { orientation ->
          trySend(
            Orientation(
              orientation =
                BearingWithAccuracy(
                  value = Bearing.North - orientation.headingDegrees.toDouble().degrees,
                  accuracy = orientation.headingErrorDegrees.toDouble().degrees,
                ),
              timestamp = TimeSource.Monotonic.markNow(),
            )
          )
        }

        orientationClient.requestOrientationUpdates(
          deviceOrientationRequest,
          dispatcher.executor,
          callback,
        )

        awaitClose { orientationClient.removeOrientationUpdates(callback) }
      }
      .sample(deviceOrientationRequest.samplingPeriodMicros.microseconds.inWholeMilliseconds)
      .stateIn(coroutineScope, sharingStarted, null)

  private companion object {
    private val dispatcher =
      Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "FusedOrientationProvider").apply { isDaemon = true }
        }
        .asCoroutineDispatcher()
  }
}

/** Create and remember a [FusedOrientationProvider] with the provided [deviceOrientationRequest] */
@Composable
public fun rememberFusedOrientationProvider(
  deviceOrientationRequest: DeviceOrientationRequest = defaultDeviceOrientationRequest,
  context: Context = LocalContext.current,
): FusedOrientationProvider {
  val orientationClient =
    remember(context) { LocationServices.getFusedOrientationProviderClient(context) }
  return rememberFusedOrientationProvider(orientationClient, deviceOrientationRequest)
}

/**
 * Create and remember a [FusedOrientationProvider] with the provided [deviceOrientationRequest] and
 * [FusedOrientationProviderClient]
 */
@Composable
public fun rememberFusedOrientationProvider(
  fusedOrientationProviderClient: FusedOrientationProviderClient,
  deviceOrientationRequest: DeviceOrientationRequest = defaultDeviceOrientationRequest,
  coroutineScope: CoroutineScope = rememberCoroutineScope(),
  sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
): FusedOrientationProvider {
  return remember(fusedOrientationProviderClient) {
    FusedOrientationProvider(
      orientationClient = fusedOrientationProviderClient,
      deviceOrientationRequest = deviceOrientationRequest,
      coroutineScope = coroutineScope,
      sharingStarted = sharingStarted,
    )
  }
}

private val defaultDeviceOrientationRequest =
  DeviceOrientationRequest.Builder(1.seconds.inWholeMicroseconds).build()
