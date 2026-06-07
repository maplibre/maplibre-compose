package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.inMeters
import org.maplibre.spatialk.units.extensions.meters
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyBestForNavigation
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.CoreLocation.kCLLocationAccuracyReduced
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSinceNow
import platform.darwin.NSObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A [LocationProvider] built on [CLLocationManager].
 *
 * Lifecycle is managed explicitly via [start] and [stop].
 * Use [rememberIosLocationProvider] for automatic Compose lifecycle binding combined with LocalLifecycleOwner.
 *
 * @param minDistance The minimum distance in meters between location updates.
 * @param desiredAccuracy The desired accuracy of the location updates.
 * @param enableLocation Whether to enable location updates.
 * @param enableOrientation Whether to enable orientation updates.
 * @param orientationUpdateInterval The interval between orientation updates.
 */
public class IosLocationProvider(
    private val minDistance: Length,
    private val desiredAccuracy: DesiredAccuracy,
    private val enableLocation: Boolean,
    private val enableOrientation: Boolean,
    private val orientationUpdateInterval: Duration,
) : LocationProvider, OrientationProvider {

    init {
        val status = CLLocationManager.authorizationStatus()
        if (
            enableLocation &&
            status != kCLAuthorizationStatusAuthorizedAlways &&
            status != kCLAuthorizationStatusAuthorizedWhenInUse
        ) {
            throw PermissionException()
        }
    }

    private val _location = MutableStateFlow<Location?>(null)
    override val location: StateFlow<Location?> = _location.asStateFlow()

    private val _orientation = MutableStateFlow<Orientation?>(null)
    override val orientation: StateFlow<Orientation?> = _orientation.asStateFlow()

    private var lastOrientationUpdate = TimeSource.Monotonic.markNow() - orientationUpdateInterval

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val locations = didUpdateLocations as? List<CLLocation> ?: return
            locations.lastOrNull()?.let { _location.value = it.asMapLibreLocation() }
        }

        override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
            if (lastOrientationUpdate.elapsedNow() >= orientationUpdateInterval) {
                _orientation.value = didUpdateHeading.asMapLibreOrientation()
                lastOrientationUpdate = TimeSource.Monotonic.markNow()
            }
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            // CLLocationManager will retry automatically; nothing to act on here.
        }
    }

    private val locationManager = CLLocationManager().apply {
        this.delegate = this@IosLocationProvider.delegate

        if (enableLocation) {
            this.desiredAccuracy =
                when (this@IosLocationProvider.desiredAccuracy) {
                    DesiredAccuracy.Highest -> kCLLocationAccuracyBestForNavigation
                    DesiredAccuracy.High -> kCLLocationAccuracyBest
                    DesiredAccuracy.Balanced -> kCLLocationAccuracyHundredMeters
                    DesiredAccuracy.Low -> kCLLocationAccuracyKilometer
                    DesiredAccuracy.Lowest -> kCLLocationAccuracyReduced
                }
            distanceFilter = minDistance.inMeters
        }
    }

    /**
     * Start updating location and orientation.
     */
    public fun start() {
        locationManager.delegate = this@IosLocationProvider.delegate
        if (enableLocation) {
            locationManager.startUpdatingLocation()
        }
        if (enableOrientation && CLLocationManager.headingAvailable()) {
            locationManager.startUpdatingHeading()
        }
    }

    /**
     * Stop updating location and orientation.
     */
    public fun stop() {
        if (enableLocation) {
            locationManager.stopUpdatingLocation()
        }
        if (enableOrientation) {
            locationManager.stopUpdatingHeading()
        }
        locationManager.delegate = null
    }
}

/**
 * Maps a [CLHeading] to a MapLibre [Orientation].
 *
 * This function handles invalid heading values by returning a `null` orientation if the
 * [CLHeading.headingAccuracy] is negative or if no valid heading value is available.
 */
private fun CLHeading.asMapLibreOrientation(): Orientation {
    val rawHeading = if (trueHeading >= 0.0) trueHeading else magneticHeading
    val bearingWithAccuracy = if (headingAccuracy >= 0.0 && rawHeading >= 0.0) {
        BearingWithAccuracy(
            value = Bearing.North + rawHeading.degrees,
            accuracy = headingAccuracy.degrees
        )
    } else {
        null
    }
    val age = (-timestamp.timeIntervalSinceNow).seconds

    return Orientation(
        orientation = bearingWithAccuracy,
        timestamp = TimeSource.Monotonic.markNow() - age,
    )
}

/**
 * Create and remember a default [LocationProvider] for iOS.
 *
 * @param updateInterval The desired interval between location updates. Not all providers support
 *   this.
 * @param desiredAccuracy The desired accuracy of the location updates.
 * @param minDistance The minimum distance in meters between location updates.
 * @return A remembered [LocationProvider] instance.
 */
@Composable
public actual fun rememberDefaultLocationProvider(
    @Suppress("UNUSED_PARAMETER")
    updateInterval: Duration,
    desiredAccuracy: DesiredAccuracy,
    minDistance: Length,
): LocationProvider {
    return rememberIosLocationProvider(
        minDistance = minDistance,
        desiredAccuracy = desiredAccuracy,
    )
}

/**
 * Create, remember, and lifecycle-bind an [IosLocationProvider]. Starts on entrance, stops on leave
 * — no manual cleanup needed.
 *
 * @param minDistance The minimum distance in meters between location updates.
 * @param desiredAccuracy The desired accuracy of the location updates.
 * @param enableLocation Whether to enable location updates.
 * @param enableOrientation Whether to enable orientation updates.
 * @param orientationUpdateInterval The interval between orientation updates.
 * @return A remembered [IosLocationProvider] instance.
 */
@Composable
public fun rememberIosLocationProvider(
    minDistance: Length = 1.meters,
    desiredAccuracy: DesiredAccuracy = DesiredAccuracy.High,
    enableLocation: Boolean = true,
    enableOrientation: Boolean = false,
    orientationUpdateInterval: Duration = 200.milliseconds,
): IosLocationProvider {
    val provider = remember(
        minDistance,
        desiredAccuracy,
        enableLocation,
        enableOrientation,
        orientationUpdateInterval,
    ) {
        IosLocationProvider(
            minDistance = minDistance,
            desiredAccuracy = desiredAccuracy,
            enableLocation = enableLocation,
            enableOrientation = enableOrientation,
            orientationUpdateInterval = orientationUpdateInterval,
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(provider, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                provider.start()
                awaitCancellation()
            } finally {
                provider.stop()
            }
        }
    }

    return provider
}

/**
 * Create and remember an [IosLocationProvider] for both location and orientation updates.
 *
 * @param minDistance The minimum distance in meters between location updates.
 * @param desiredAccuracy The desired accuracy of the location updates.
 * @param orientationUpdateInterval The interval between orientation updates.
 * @return A remembered [IosLocationProvider] instance.
 */
@Composable
public fun rememberIosLocationAndOrientationProvider(
    minDistance: Length = 1.meters,
    desiredAccuracy: DesiredAccuracy = DesiredAccuracy.High,
    orientationUpdateInterval: Duration = 200.milliseconds,
): IosLocationProvider {
    return rememberIosLocationProvider(
        minDistance = minDistance,
        desiredAccuracy = desiredAccuracy,
        enableLocation = true,
        enableOrientation = true,
        orientationUpdateInterval = orientationUpdateInterval,
    )
}
