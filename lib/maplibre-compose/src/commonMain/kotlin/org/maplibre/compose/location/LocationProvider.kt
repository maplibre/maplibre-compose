package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.extensions.inMeters
import org.maplibre.spatialk.units.extensions.meters

/**
 * This is an intentionally very limited abstraction over the various platform APIs for geolocation.
 * It is specialized to the use case of maplibre-compose.
 *
 * If you have a more general use case, prefer using the platform APIs directly or using a more
 * powerful wrapper. In that case, you may want to provide your own [LocationProvider]
 * implementation to unify the API underneath. This is an explicitly supported use case.
 *
 * Collecting [updates] owns one foreground session. Cancelling collection must release that
 * session's callbacks and resources.
 */
public interface LocationProvider {
  /** Whether this provider has a location backend on the current platform and host. */
  public val isSupported: Boolean
    get() = true

  /** The foreground permission controller associated with this provider. */
  public val permission: LocationPermissionController

  /**
   * Creates a cold stream for one foreground location session.
   *
   * Collection starts the platform session. Cancelling collection releases every callback and
   * native resource that belongs to that session.
   */
  public fun updates(request: LocationRequest): Flow<LocationEvent>
}

/**
 * Preferences for a foreground location session.
 *
 * A provider applies each preference when its platform supports that setting. A platform may ignore
 * a preference that its location API cannot express.
 *
 * @property accuracy Requested accuracy and power tradeoff.
 * @property minimumInterval Preferred minimum time between delivered locations.
 * @property minimumDistance Preferred minimum movement between delivered locations.
 * @property maximumInitialFixAge Oldest cached measurement that may start a session, or `null` to
 *   accept a cached measurement of any age.
 */
public data class LocationRequest(
  val accuracy: LocationAccuracy = LocationAccuracy.High,
  val minimumInterval: Duration = 1.seconds,
  val minimumDistance: Length = 1.meters,
  val maximumInitialFixAge: Duration? = 30.seconds,
) {
  init {
    require(!minimumInterval.isNegative()) { "minimumInterval must not be negative" }
    require(minimumDistance.inMeters >= 0.0) { "minimumDistance must not be negative" }
    require(maximumInitialFixAge?.isNegative() != true) {
      "maximumInitialFixAge must not be negative"
    }
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

/** Events from one collected location session. */
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

/** Reasons that a location session cannot currently deliver measurements. */
public enum class LocationUnavailableReason {
  /**
   * The device's location services are disabled.
   *
   * For example, the user turned off Location Services in system settings while the application was
   * running.
   */
  ServicesDisabled,

  /**
   * The provider cannot deliver a location now, but a later update or session may succeed.
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
   * [canRequest] is `true` when the platform reports that the application can request
   * authorization, `false` when it reports that authorization must change outside the application,
   * and `null` when the platform does not expose that distinction.
   */
  public data class NotGranted(val canRequest: Boolean?) : LocationPermission
}

/** Observes and requests foreground location authorization. */
public interface LocationPermissionController {
  public val status: StateFlow<LocationPermission>

  /** Requests foreground authorization and may present platform permission UI. */
  public suspend fun requestForegroundPermission(): LocationPermission
}

internal class FixedLocationPermissionController(initial: LocationPermission) :
  LocationPermissionController {
  override val status: StateFlow<LocationPermission> = MutableStateFlow(initial)

  override suspend fun requestForegroundPermission(): LocationPermission = status.value
}

/** A provider for a target or host that has no installed location implementation. */
public object UnsupportedLocationProvider : LocationProvider {
  override val isSupported: Boolean = false
  override val permission: LocationPermissionController =
    FixedLocationPermissionController(LocationPermission.NotGranted(canRequest = null))

  override fun updates(request: LocationRequest): Flow<LocationEvent> =
    flowOf(LocationEvent.Unavailable(LocationUnavailableReason.Unsupported))
}

/**
 * Creates and remembers the default location provider for the current platform.
 *
 * Platform-specific provider factories remain available when an application needs configuration
 * beyond [LocationRequest]. An unsupported target or desktop host returns a provider that reports
 * [LocationUnavailableReason.Unsupported] instead of throwing during composition.
 *
 * See [AndroidLocationProvider][org.maplibre.compose.location.AndroidLocationProvider] and
 * [IosLocationProvider][org.maplibre.compose.location.IosLocationProvider].
 */
@Composable public expect fun rememberDefaultLocationProvider(): LocationProvider

/** Returns the first fix from a location session. */
public suspend fun LocationProvider.currentLocation(
  request: LocationRequest = LocationRequest(),
  timeout: Duration = 30.seconds,
): Location =
  withTimeout(timeout) { updates(request).filterIsInstance<LocationEvent.Fix>().first().location }
