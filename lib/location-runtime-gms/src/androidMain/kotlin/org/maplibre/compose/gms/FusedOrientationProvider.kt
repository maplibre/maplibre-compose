package org.maplibre.compose.gms

import com.google.android.gms.location.DeviceOrientation
import com.google.android.gms.location.DeviceOrientationRequest
import com.google.android.gms.location.FusedOrientationProviderClient
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.microseconds
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
 * An [OrientationProvider] backed by the Google Play Services
 * [`FusedOrientationProviderClient`](https://developers.google.com/android/reference/com/google/android/gms/location/FusedOrientationProviderClient).
 *
 * [`DeviceOrientation.headingDegrees`](https://developers.google.com/android/reference/com/google/android/gms/location/DeviceOrientation#getHeadingDegrees())
 * maps to [Orientation.orientation], and
 * [`DeviceOrientation.headingErrorDegrees`](https://developers.google.com/android/reference/com/google/android/gms/location/DeviceOrientation#getHeadingErrorDegrees())
 * maps to its accuracy.
 *
 * @param orientationClient Client used to request orientation updates.
 * @param deviceOrientationRequest Platform request passed to the client.
 * @param coroutineScope Scope used to share the [orientation] flow.
 * @param sharingStarted Sharing policy for the [orientation] flow.
 */
public class FusedOrientationProvider(
  private val orientationClient: FusedOrientationProviderClient,
  private val deviceOrientationRequest: DeviceOrientationRequest,
  coroutineScope: CoroutineScope,
  sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
) : OrientationProvider {
  @OptIn(FlowPreview::class)
  override val orientation: StateFlow<Orientation?> = callbackFlow {
    val callback: (DeviceOrientation) -> Unit = { orientation ->
      trySend(
        Orientation(
          orientation =
            BearingWithAccuracy(
              value = Bearing.North + orientation.headingDegrees.toDouble().degrees,
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
