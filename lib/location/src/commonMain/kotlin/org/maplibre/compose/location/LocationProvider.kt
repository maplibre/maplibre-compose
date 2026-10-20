package org.maplibre.compose.location

import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.extensions.inMeters
import org.maplibre.spatialk.units.extensions.meters

/**
 * Supplies foreground location updates and permission status from [backend].
 *
 * Platform factories create providers with the appropriate backend. For a custom location source,
 * implement [LocationBackend] and pass it to this constructor.
 */
public class LocationProvider(private val backend: LocationBackend) : AutoCloseable {
  /** A stable implementation name for diagnostics, when the backend declares one. */
  public val backendId: String?
    get() = backend.id

  /** Whether this provider has a usable platform implementation, stable for its lifetime. */
  public val backendAvailability: LocationBackendAvailability
    get() = backend.availability

  /** Current foreground location permission. See [LocationBackend.permission]. */
  public val permission: StateFlow<LocationPermission>
    get() = backend.permission

  /** Starts a foreground permission request. See [LocationBackend.requestPermission]. */
  public fun requestPermission(): Unit = backend.requestPermission()

  /**
   * Returns a cold stream of foreground location updates.
   *
   * An unsupported backend emits [LocationEvent.Unavailable] with
   * [LocationUnavailableReason.Unsupported] and completes without starting a platform request. A
   * misconfigured backend fails collection with [IllegalStateException] and its setup cause. An
   * available backend starts an independent request for each collector; cancellation stops it.
   */
  public fun updates(request: LocationRequest = LocationRequest.Standard): Flow<LocationEvent> =
    flow {
      when (val availability = backend.availability) {
        LocationBackendAvailability.Available -> emitAll(backend.updates(request))
        LocationBackendAvailability.Unsupported ->
          emit(LocationEvent.Unavailable(LocationUnavailableReason.Unsupported))
        is LocationBackendAvailability.Misconfigured ->
          throw IllegalStateException("Location backend is misconfigured", availability.cause)
        else -> error("Unknown location backend availability: $availability")
      }
    }

  /**
   * Releases the backend's resources. Repeated calls have no effect.
   *
   * Cancel collectors before closing. Android, iOS, and macOS backends require the main thread.
   * Unsupported providers can still report their fixed unavailable result after closing.
   */
  override fun close(): Unit = backend.close()
}

/**
 * Whether a location implementation has a usable platform backend.
 *
 * This describes application and backend setup. It does not describe location permission, system
 * location services, or whether the next request can obtain a measurement.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
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
 * Keeps [LocationBackendAvailability] open: callers' `when` needs an `else` branch. No provider in
 * this library reports it.
 */
internal data object UnspecifiedLocationBackendAvailability : LocationBackendAvailability

/**
 * Preferences for foreground location updates.
 *
 * A provider applies each preference when its platform supports that setting. A platform may ignore
 * a preference that its location API cannot express.
 *
 * @property accuracy Requested accuracy and power tradeoff.
 * @property minimumInterval Preferred minimum time between delivered locations. Must not be
 *   negative.
 * @property minimumDistance Preferred minimum movement between delivered locations. Must not be
 *   negative.
 * @throws IllegalArgumentException if [minimumInterval] or [minimumDistance] is negative.
 */
@Immutable
public data class LocationRequest
internal constructor(
  public val accuracy: LocationAccuracy,
  public val minimumInterval: Duration,
  public val minimumDistance: Length,
) {
  /** Edits [from]; omitted settings inherit. */
  public constructor(
    from: LocationRequest = Standard,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder,
  ) : this(builder.accuracy, builder.minimumInterval, builder.minimumDistance)

  init {
    require(!minimumInterval.isNegative()) {
      "minimumInterval must not be negative, was $minimumInterval"
    }
    require(minimumDistance.inMeters >= 0.0) {
      "minimumDistance must not be negative, was $minimumDistance"
    }
  }

  public class Builder internal constructor(from: LocationRequest) {
    /** See [LocationRequest.accuracy]. */
    public var accuracy: LocationAccuracy = from.accuracy

    /** See [LocationRequest.minimumInterval]. */
    public var minimumInterval: Duration = from.minimumInterval

    /** See [LocationRequest.minimumDistance]. */
    public var minimumDistance: Length = from.minimumDistance
  }

  public companion object {
    /** High accuracy with a one-second minimum interval and a one-meter minimum distance. */
    public val Standard: LocationRequest =
      LocationRequest(LocationAccuracy.High, 1.seconds, 1.meters)
  }
}

/**
 * Accuracy levels for [LocationRequest], which are mapped to platform accuracy and power levels.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 *
 * Use [High] to display the user's location, or [Balanced] to reduce power usage.
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

/**
 * Events emitted while collecting [LocationProvider.updates].
 *
 * A custom provider emits [Update] and [Unavailable].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed interface LocationEvent {
  /**
   * A location measurement delivered by the provider.
   *
   * @property measurement The delivered location measurement.
   * @property measurementMark Process-local monotonic mark for when [measurement] was measured. A
   *   provider uses the delivery time when its source has no monotonic measurement time.
   */
  public data class Update(
    val measurement: LocationMeasurement,
    val measurementMark: TimeMark,
  ) : LocationEvent

  /**
   * A condition or failure that currently prevents location delivery.
   *
   * @property reason Portable classification of the condition, or `null` when the provider has no
   *   classification for it, such as an unexpected platform failure. [cause] then describes the
   *   failure.
   * @property cause Underlying platform or provider exception, when one is available.
   */
  public data class Unavailable(
    val reason: LocationUnavailableReason?,
    val cause: Throwable? = null,
  ) : LocationEvent
}

/**
 * Keeps [LocationEvent] open: callers' `when` needs an `else` branch. No provider in this library
 * emits it.
 */
internal data object UnspecifiedLocationEvent : LocationEvent

/**
 * Reasons that a provider cannot currently deliver location measurements.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed interface LocationUnavailableReason {
  /**
   * The device's location services are disabled.
   *
   * For example, the user turned off Location Services in system settings while the application was
   * running.
   */
  public data object ServicesDisabled : LocationUnavailableReason

  /**
   * The provider cannot deliver a location now, but a later location request may succeed.
   *
   * For example, a device in a tunnel may temporarily lose satellite positioning, or a browser
   * request may time out before a position is available.
   */
  public data object TemporarilyUnavailable : LocationUnavailableReason

  /**
   * The target or host cannot provide location.
   *
   * For example, an initialized Windows provider may report that location is not available on the
   * device.
   */
  public data object Unsupported : LocationUnavailableReason

  /**
   * Foreground location permission has not been granted.
   *
   * For example, the user may deny the permission prompt or revoke permission in system settings
   * while the application is running.
   */
  public data object PermissionDenied : LocationUnavailableReason
}

/**
 * Keeps [LocationUnavailableReason] open: callers' `when` needs an `else` branch. No provider in
 * this library reports it.
 */
internal data object UnspecifiedLocationUnavailableReason : LocationUnavailableReason

/**
 * The accuracy level that the user authorized.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed interface LocationAccuracyAuthorization {
  /** Fine location on Android or full accuracy on iOS. */
  public data object Precise : LocationAccuracyAuthorization

  /** Coarse location on Android or reduced accuracy on iOS. */
  public data object Approximate : LocationAccuracyAuthorization
}

/**
 * Keeps [LocationAccuracyAuthorization] open: callers' `when` needs an `else` branch. No provider
 * in this library reports it.
 */
internal data object UnspecifiedLocationAccuracyAuthorization : LocationAccuracyAuthorization

/**
 * Current foreground location authorization.
 *
 * A custom provider reports [NotDetermined], [Granted], and [NotGranted].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed interface LocationPermission {
  /**
   * Authorization has not been determined. Collecting [LocationProvider.updates] retries the
   * permission check without prompting. The provider must determine authorization before delivering
   * measurements and report check failures through [LocationEvent.Unavailable].
   */
  public data object NotDetermined : LocationPermission

  /**
   * Foreground authorization is granted.
   *
   * @property accuracy The accuracy level that the user authorized, or `null` when the platform
   *   does not report whether precise location is authorized.
   */
  public data class Granted(val accuracy: LocationAccuracyAuthorization?) : LocationPermission

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

/**
 * Keeps [LocationPermission] open: callers' `when` needs an `else` branch. No provider in this
 * library reports it.
 */
internal data object UnspecifiedLocationPermission : LocationPermission

/** A provider for a target or host that has no installed location implementation. */
public val UnsupportedLocationProvider: LocationProvider =
  LocationProvider(UnavailableLocationBackend(LocationBackendAvailability.Unsupported))

internal class UnavailableLocationBackend(
  override val availability: LocationBackendAvailability,
) : LocationBackend {
  override fun updates(request: LocationRequest): Flow<LocationEvent> =
    error("Unavailable backends cannot start location requests")
}
