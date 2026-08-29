package org.maplibre.compose.gms

import android.os.SystemClock
import com.google.android.gms.location.DeviceOrientation
import com.google.android.gms.location.DeviceOrientationRequest
import com.google.android.gms.location.FusedOrientationProviderClient
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.maplibre.compose.location.Heading
import org.maplibre.compose.location.HeadingProvider
import org.maplibre.compose.location.HeadingReference
import org.maplibre.compose.location.HeadingRequest
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees

/**
 * A [HeadingProvider] backed by the Google Play Services
 * [`FusedOrientationProviderClient`](https://developers.google.com/android/reference/com/google/android/gms/location/FusedOrientationProviderClient).
 *
 * [`DeviceOrientation.headingDegrees`](https://developers.google.com/android/reference/com/google/android/gms/location/DeviceOrientation#getHeadingDegrees())
 * maps to [Heading.bearing]. [Heading.reference] is [HeadingReference.TrueOrMagneticNorth] because
 * Google Play Services uses true north when magnetic declination is available and magnetic north
 * otherwise. The API does not report the selected reference. The
 * [`DeviceOrientation.headingErrorDegrees`](https://developers.google.com/android/reference/com/google/android/gms/location/DeviceOrientation#getHeadingErrorDegrees())
 * maps to its accuracy. The value `180`, which denotes complete ignorance, maps to `null`.
 *
 * @param orientationClient Client used to request orientation updates.
 */
public class FusedHeadingProvider
internal constructor(
  private val orientationClient: FusedOrientationProviderClient,
  private val elapsedRealtimeNanos: () -> Long,
) : HeadingProvider {
  public constructor(
    orientationClient: FusedOrientationProviderClient
  ) : this(orientationClient, SystemClock::elapsedRealtimeNanos)

  override fun updates(request: HeadingRequest): Flow<Heading> = callbackFlow {
    val deviceOrientationRequest =
      DeviceOrientationRequest.Builder(request.minimumInterval.inWholeMicroseconds).build()
    val callback: (DeviceOrientation) -> Unit = { orientation ->
      trySend(
        Heading(
          bearing = Bearing.North + orientation.headingDegrees.toDouble().degrees,
          reference = HeadingReference.TrueOrMagneticNorth,
          accuracy = orientation.headingErrorDegrees.takeIf { it < 180f }?.toDouble()?.degrees,
          measuredAt =
            Clock.System.now() -
              (elapsedRealtimeNanos() - orientation.elapsedRealtimeNs).coerceAtLeast(0).nanoseconds,
        )
      )
    }

    val registration =
      orientationClient.requestOrientationUpdates(
        deviceOrientationRequest,
        dispatcher.executor,
        callback,
      )
    registration.addOnFailureListener(dispatcher.executor) { error -> close(error) }

    awaitClose {
      registration.addOnCompleteListener(dispatcher.executor) {
        orientationClient.removeOrientationUpdates(callback)
      }
    }
  }

  private companion object {
    private val dispatcher =
      Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "FusedHeadingProvider").apply { isDaemon = true }
        }
        .asCoroutineDispatcher()
  }
}
