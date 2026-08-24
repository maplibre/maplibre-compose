package org.maplibre.compose.location

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.extensions.inMeters
import org.maplibre.spatialk.units.extensions.meters

/**
 * This is an intentionally very limited abstraction over the various platform APIs for geolocation.
 * It is specialized to the use case of maplibre-compose.
 *
 * If you have a more general use case, prefer using the platform APIs directly or using a more
 * powerful wrapper. In that case, you may want to provide your own [LocationProvider]
 * implementation to unify the API underneath. This is an explicitly supported use case:
 * [permission] defaults to granted, so a source where permission is not a concept needs no
 * permission handling.
 *
 * Each collector of [updates] starts an independent platform location request. Cancelling
 * collection must stop that request and unregister its callbacks.
 */
public interface LocationProvider {
  /** A stable implementation name for diagnostics, when the provider declares one. */
  public val backendId: String?
    get() = null

  /** Whether this provider has a usable platform implementation. */
  public val backendAvailability: LocationBackendAvailability
    get() = LocationBackendAvailability.Available

  /**
   * Current foreground location permission.
   *
   * This value also reflects changes made outside the application when the platform reports them.
   *
   * The default is always [LocationPermission.Granted] at [LocationAccuracyAuthorization.Unknown].
   * A source where permission is not a concept, such as an external receiver or a network feed,
   * keeps the default and needs no permission handling.
   */
  public val permission: StateFlow<LocationPermission>
    get() = AlwaysGrantedLocationPermission

  /**
   * Starts a foreground permission request and returns immediately.
   *
   * The result is published to [permission]. Calls made while a request is active must not start
   * another platform request.
   *
   * The default does nothing, to match the default [permission] that is always granted.
   */
  public fun requestPermission(): Unit = Unit

  /**
   * Returns a cold stream of foreground location updates.
   *
   * Each collector starts an independent platform location request. Cancelling collection stops
   * that request and unregisters its callbacks.
   */
  public fun updates(request: LocationRequest): Flow<LocationEvent>
}

private val AlwaysGrantedLocationPermission: StateFlow<LocationPermission> =
  MutableStateFlow(LocationPermission.Granted(LocationAccuracyAuthorization.Unknown))

/**
 * Whether a location implementation has a usable platform backend.
 *
 * This describes application and backend setup. It does not describe location permission, system
 * location services, or whether the next request can obtain a fix.
 */
public sealed interface LocationBackendAvailability {
  /** A platform implementation is installed and initialized. */
  public data object Available : LocationBackendAvailability

  /**
   * No implementation can run on this target or host.
   *
   * For example, a browser may omit its Geolocation API, or a desktop runtime may omit the location
   * artifact for its operating system.
   */
  public data object Unsupported : LocationBackendAvailability

  /**
   * An installed implementation could not be selected or initialized.
   *
   * For example, a desktop runtime may contain two location backends when exactly one is required.
   *
   * @property cause The underlying setup failure, when one is available.
   */
  public data class Misconfigured(val cause: Throwable? = null) : LocationBackendAvailability
}

/**
 * Preferences for foreground location updates.
 *
 * A provider applies each preference when its platform supports that setting. A platform may ignore
 * a preference that its location API cannot express.
 *
 * @property accuracy Requested accuracy and power tradeoff.
 * @property minimumInterval Preferred minimum time between delivered locations.
 * @property minimumDistance Preferred minimum movement between delivered locations.
 */
public data class LocationRequest(
  val accuracy: LocationAccuracy = LocationAccuracy.High,
  val minimumInterval: Duration = 1.seconds,
  val minimumDistance: Length = 1.meters,
) {
  init {
    require(!minimumInterval.isNegative()) { "minimumInterval must not be negative" }
    require(minimumDistance.inMeters >= 0.0) { "minimumDistance must not be negative" }
  }
}

/**
 * Accuracy levels for [LocationRequest], which are mapped to platform accuracy and power levels.
 *
 * The recommended level for actively displaying the user location on screen is [High] or
 * [Balanced], if the location doesn't have to be perfectly accurate.
 */
public enum class LocationAccuracy {
  /**
   * Request the highest possible accuracy and accept high power usage.
   *
   * Prefer [High] unless the application performs real-time navigation.
   */
  BestForNavigation,

  /** Request very high accuracy and accept high power usage. */
  High,

  /** Request a balance of accuracy and power usage. */
  Balanced,

  /** Request a rough location with low power usage. */
  Low,

  /** Request any location with the lowest possible power usage. */
  Lowest,
}

/** Events emitted while collecting [LocationProvider.updates]. */
public sealed interface LocationEvent {
  /** A measured location. */
  public data class Fix(val location: Location) : LocationEvent

  /**
   * A condition or failure that currently prevents location delivery.
   *
   * @property reason Portable classification of the condition.
   * @property cause Underlying platform or provider exception, when one is available.
   */
  public data class Unavailable(
    val reason: LocationUnavailableReason,
    val cause: Throwable? = null,
  ) : LocationEvent
}

/** Reasons that a provider cannot currently deliver location measurements. */
public enum class LocationUnavailableReason {
  /**
   * The device's location services are disabled.
   *
   * For example, the user turned off Location Services in system settings while the application was
   * running.
   */
  ServicesDisabled,

  /**
   * The provider cannot deliver a location now, but a later location request may succeed.
   *
   * For example, a device in a tunnel may temporarily lose its GPS fix, or a browser request may
   * time out before a position is available.
   */
  TemporarilyUnavailable,

  /**
   * No location implementation is available for the current target or host.
   *
   * For example, a browser may omit the Geolocation API, or a desktop application may omit the
   * location backend for its operating system.
   */
  Unsupported,

  /**
   * The application installed or configured its provider incorrectly.
   *
   * For example, a desktop runtime may contain multiple location backends when exactly one is
   * required.
   */
  Misconfigured,

  /**
   * Foreground location permission has not been granted.
   *
   * For example, the user may deny the permission prompt or revoke permission in system settings
   * while the application is running.
   */
  PermissionDenied,

  /**
   * The provider failed for a reason that is not a normal availability condition.
   *
   * For example, a platform service may return malformed location data, or a custom provider may
   * throw while its update flow is being collected.
   */
  UnexpectedFailure,
}

/** The accuracy level that the user authorized. */
public enum class LocationAccuracyAuthorization {
  /** Fine location on Android or full accuracy on iOS. */
  Precise,

  /** Coarse location on Android or reduced accuracy on iOS. */
  Approximate,

  /** The platform does not report whether precise location is authorized. */
  Unknown,
}

/** Current foreground location authorization. */
public sealed interface LocationPermission {
  /** Foreground authorization is granted at [accuracy]. */
  public data class Granted(val accuracy: LocationAccuracyAuthorization) : LocationPermission

  /**
   * Foreground authorization is not granted.
   *
   * @property canRequest `true` when the platform reports that the application can request
   *   authorization, `false` when it reports that authorization must change outside the
   *   application, and `null` when the platform does not expose that distinction.
   * @property shouldShowRationale `true` when the platform advises explaining the request before
   *   making it, which implies `canRequest = true`. Android reports it from
   *   [`shouldShowRequestPermissionRationale`](https://developer.android.com/reference/android/app/Activity#shouldShowRequestPermissionRationale(java.lang.String));
   *   every other platform reports `false`.
   */
  public data class NotGranted(
    val canRequest: Boolean?,
    val shouldShowRationale: Boolean = false,
  ) : LocationPermission
}

/** A provider for a target or host that has no installed location implementation. */
public object UnsupportedLocationProvider : LocationProvider {
  override val backendAvailability: LocationBackendAvailability =
    LocationBackendAvailability.Unsupported

  override fun updates(request: LocationRequest): Flow<LocationEvent> =
    flowOf(LocationEvent.Unavailable(LocationUnavailableReason.Unsupported))
}
