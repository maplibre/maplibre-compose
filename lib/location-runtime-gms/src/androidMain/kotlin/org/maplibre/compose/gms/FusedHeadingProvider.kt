package org.maplibre.compose.gms

import android.os.SystemClock
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.DeviceOrientation
import com.google.android.gms.location.DeviceOrientationRequest
import com.google.android.gms.location.FusedOrientationProviderClient
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import org.maplibre.compose.location.HeadingEvent
import org.maplibre.compose.location.HeadingMeasurement
import org.maplibre.compose.location.HeadingProvider
import org.maplibre.compose.location.HeadingReference
import org.maplibre.compose.location.HeadingRequest
import org.maplibre.compose.location.HeadingUnavailableReason
import org.maplibre.compose.location.throttleUpdates
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees

/**
 * A [HeadingProvider] backed by the Google Play Services
 * [`FusedOrientationProviderClient`](https://developers.google.com/android/reference/com/google/android/gms/location/FusedOrientationProviderClient).
 *
 * [`DeviceOrientation.headingDegrees`](https://developers.google.com/android/reference/com/google/android/gms/location/DeviceOrientation#getHeadingDegrees())
 * maps to [HeadingMeasurement.bearing]. [HeadingMeasurement.reference] is
 * [HeadingReference.TrueOrMagneticNorth] because Google Play Services uses true north when magnetic
 * declination is available and magnetic north otherwise. The API does not report the selected
 * reference. The
 * [`DeviceOrientation.headingErrorDegrees`](https://developers.google.com/android/reference/com/google/android/gms/location/DeviceOrientation#getHeadingErrorDegrees())
 * maps to its accuracy. The value `180`, which denotes complete ignorance of the heading, reports
 * [HeadingUnavailableReason.Unreliable].
 *
 * @param orientationClient Client used to request orientation updates.
 */
public class FusedHeadingProvider
internal constructor(
  private val orientationClient: FusedOrientationProviderClient,
  private val elapsedRealtimeNanos: () -> Long,
  private val executor: Executor = dispatcher.executor,
) : HeadingProvider {
  public constructor(
    orientationClient: FusedOrientationProviderClient,
  ) : this(orientationClient, SystemClock::elapsedRealtimeNanos)

  override fun updates(request: HeadingRequest): Flow<HeadingEvent> =
    callbackFlow<HeadingEvent> {
        val deviceOrientationRequest =
          DeviceOrientationRequest.Builder(request.minimumInterval.inWholeMicroseconds).build()
        val callback: (DeviceOrientation) -> Unit = callback@{ orientation ->
          if (orientation.headingErrorDegrees >= 180f) {
            trySend(HeadingEvent.Unavailable(HeadingUnavailableReason.Unreliable))
            return@callback
          }
          trySend(
            HeadingEvent.Update(
              HeadingMeasurement(
                bearing = Bearing.North + orientation.headingDegrees.toDouble().degrees,
                reference = HeadingReference.TrueOrMagneticNorth,
                accuracy = orientation.headingErrorDegrees.toDouble().degrees,
                measuredAt =
                  Clock.System.now() -
                    (elapsedRealtimeNanos() - orientation.elapsedRealtimeNs)
                      .coerceAtLeast(0)
                      .nanoseconds,
              ),
            ),
          )
        }

        val registration =
          orientationClient.requestOrientationUpdates(
            deviceOrientationRequest,
            executor,
            callback,
          )
        registration.addOnFailureListener(executor) { error -> close(error) }

        awaitClose {
          registration.addOnCompleteListener(executor) {
            orientationClient.removeOrientationUpdates(callback)
          }
        }
      }
      .catch { error ->
        if (error !is ApiException) throw error
        emit(HeadingEvent.Unavailable(reason = null, cause = error))
      }
      .distinctUntilChanged()
      .throttleUpdates(request.minimumInterval)

  private companion object {
    private val dispatcher =
      Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "FusedHeadingProvider").apply { isDaemon = true }
        }
        .asCoroutineDispatcher()
  }
}
