package org.maplibre.compose.location

import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLErrorDomain
import platform.CoreLocation.kCLErrorHeadingFailure
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

/**
 * Device heading provider backed by
 * [`CLLocationManager`](https://developer.apple.com/documentation/corelocation/cllocationmanager).
 *
 * A nonnegative
 * [`CLHeading.trueHeading`](https://developer.apple.com/documentation/corelocation/clheading/trueheading)
 * maps to [HeadingMeasurement.bearing] with [HeadingReference.TrueNorth]. Otherwise,
 * [`CLHeading.magneticHeading`](https://developer.apple.com/documentation/corelocation/clheading/magneticheading)
 * maps to [HeadingMeasurement.bearing] with [HeadingReference.MagneticNorth]. A negative
 * [`CLHeading.headingAccuracy`](https://developer.apple.com/documentation/corelocation/clheading/headingaccuracy)
 * marks the heading as invalid. The provider ignores that callback. A valid accuracy maps to
 * [HeadingMeasurement.accuracy].
 *
 * A device without a magnetometer reports [LocationUnavailableReason.Unsupported]. Magnetic
 * interference reports [LocationUnavailableReason.TemporarilyUnavailable], and other Core Location
 * failures report a `null` reason. Collection continues after a failure, because Core Location
 * keeps trying.
 */
public class IosHeadingProvider
internal constructor(
  private val isHeadingAvailable: () -> Boolean,
  private val coroutineContext: CoroutineContext,
) : HeadingProvider {
  public constructor() : this({ CLLocationManager.headingAvailable() }, Dispatchers.Main)

  override fun updates(request: HeadingRequest): Flow<HeadingEvent> = callbackFlow {
    if (!isHeadingAvailable()) {
      send(HeadingEvent.Unavailable(LocationUnavailableReason.Unsupported))
      close()
      return@callbackFlow
    }
    val manager = CLLocationManager()
    val delegate = IosHeadingDelegate(channel)
    manager.delegate = delegate
    manager.startUpdatingHeading()
    // Retaining the delegate in this closure is required because CLLocationManager does not.
    awaitClose { delegate.stop(manager) }
  }
    .flowOn(coroutineContext)
    .throttleUpdates(request.minimumInterval)
}

internal class IosHeadingDelegate(private val channel: SendChannel<HeadingEvent>) :
  NSObject(), CLLocationManagerDelegateProtocol {
  override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
    if (didUpdateHeading.headingAccuracy < 0.0) return
    val hasTrueHeading = didUpdateHeading.trueHeading >= 0.0
    val heading =
      if (hasTrueHeading) didUpdateHeading.trueHeading else didUpdateHeading.magneticHeading
    channel.trySend(
      HeadingEvent.Update(
        HeadingMeasurement(
          bearing = Bearing.North + heading.degrees,
          reference =
            if (hasTrueHeading) HeadingReference.TrueNorth else HeadingReference.MagneticNorth,
          accuracy = didUpdateHeading.headingAccuracy.degrees,
          measuredAt =
            Instant.fromEpochMilliseconds(
              (didUpdateHeading.timestamp.timeIntervalSince1970 * 1_000).toLong(),
            ),
        ),
      ),
    )
  }

  override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
    val headingFailure =
      didFailWithError.domain == kCLErrorDomain && didFailWithError.code == kCLErrorHeadingFailure
    channel.trySend(
      HeadingEvent.Unavailable(
        reason = if (headingFailure) LocationUnavailableReason.TemporarilyUnavailable else null,
        cause = IosHeadingException(didFailWithError),
      ),
    )
  }

  fun stop(manager: CLLocationManager) {
    manager.stopUpdatingHeading()
    manager.delegate = null
  }
}

internal class IosHeadingException(val error: NSError) :
  RuntimeException(error.localizedDescription)
