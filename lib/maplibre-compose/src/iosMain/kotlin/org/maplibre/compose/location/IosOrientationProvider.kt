package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.Foundation.timeIntervalSinceNow
import platform.darwin.NSObject

/** Device heading provider backed by Core Location. */
@OptIn(FlowPreview::class)
public class IosOrientationProvider(
  updateInterval: Duration,
  coroutineScope: CoroutineScope,
  sharingStarted: SharingStarted,
) : OrientationProvider {
  override val orientation: StateFlow<Orientation?> = callbackFlow {
    val manager = CLLocationManager()
    val delegate =
      object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
          val heading =
            if (didUpdateHeading.trueHeading >= 0.0) didUpdateHeading.trueHeading
            else didUpdateHeading.magneticHeading
          val accuracy =
            if (didUpdateHeading.headingAccuracy >= 0.0) {
              didUpdateHeading.headingAccuracy.degrees
            } else {
              null
            }
          val age = (-didUpdateHeading.timestamp.timeIntervalSinceNow).seconds
          trySend(
            Orientation(
              BearingWithAccuracy(Bearing.North + heading.degrees, accuracy),
              TimeSource.Monotonic.markNow() - age,
            )
          )
        }
      }
    manager.delegate = delegate
    if (CLLocationManager.headingAvailable()) manager.startUpdatingHeading()
    awaitClose {
      manager.stopUpdatingHeading()
      manager.delegate = null
    }
  }
    .flowOn(Dispatchers.Main)
    .sample(updateInterval)
    .stateIn(coroutineScope, sharingStarted, null)
}

@Composable
public actual fun rememberDefaultOrientationProvider(
  updateInterval: Duration
): OrientationProvider = rememberIosOrientationProvider(updateInterval)

@Composable
public fun rememberIosOrientationProvider(
  updateInterval: Duration = 1.seconds,
  coroutineScope: CoroutineScope = rememberCoroutineScope(),
  sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
): IosOrientationProvider =
  remember(updateInterval, coroutineScope, sharingStarted) {
    IosOrientationProvider(updateInterval, coroutineScope, sharingStarted)
  }
