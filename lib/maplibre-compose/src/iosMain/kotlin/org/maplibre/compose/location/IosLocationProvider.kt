package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.extensions.centimeters
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.inMeters
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

/**
 * A [LocationProvider] built on [CLLocationManager].
 *
 * Lifecycle is managed explicitly via [start] and [stop].
 * Use [rememberIosLocationProvider] for automatic Compose lifecycle binding.
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

    private var lastOrientationUpdate =
        TimeSource.Monotonic.markNow() - orientationUpdateInterval

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

    public fun start() {
        if (enableLocation) {
            locationManager.startUpdatingLocation()
        }
        if (enableOrientation && CLLocationManager.headingAvailable()) {
            locationManager.startUpdatingHeading()
        }
    }

    public fun stop() {
        locationManager.stopUpdatingLocation()
        locationManager.stopUpdatingHeading()
        locationManager.delegate = null
    }
}

private fun CLHeading.asMapLibreOrientation(): Orientation {
    val heading = if (trueHeading >= 0.0) trueHeading else magneticHeading
    val accuracy = if (headingAccuracy >= 0.0) headingAccuracy.degrees else null
    val age = (-timestamp.timeIntervalSinceNow).seconds

    return Orientation(
        orientation = BearingWithAccuracy(
            value = Bearing.North + heading.degrees,
            accuracy = accuracy
        ),
        timestamp = TimeSource.Monotonic.markNow() - age,
    )
}

@Composable
public actual fun rememberDefaultLocationProvider(
    updateInterval: Duration,
    desiredAccuracy: DesiredAccuracy,
    minDistance: Length,
): LocationProvider {
    return rememberIosLocationAndOrientationProvider(
        minDistance = minDistance,
        desiredAccuracy = desiredAccuracy,
    )
}

/**
 * Create, remember, and lifecycle-bind an [IosLocationProvider].
 * Starts on entrance, stops on leave — no manual cleanup needed.
 */
@Composable
public fun rememberIosLocationProvider(
    minDistance: Length = 50.centimeters,
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

    DisposableEffect(provider) {
        provider.start()
        onDispose { provider.stop() }
    }

    return provider
}

/**
 * Create and remember an [IosLocationProvider] for both location and orientation updates.
 */
@Composable
public fun rememberIosLocationAndOrientationProvider(
    minDistance: Length = 50.centimeters,
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
