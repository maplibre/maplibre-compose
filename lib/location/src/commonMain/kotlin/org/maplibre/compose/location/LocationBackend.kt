package org.maplibre.compose.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Supplies foreground location updates and permission status.
 *
 * Implement this interface for custom location sources and wrap it in [LocationProvider]. Sources
 * without permission handling can use the default [permission] and [requestPermission]
 * implementations.
 */
public interface LocationBackend : AutoCloseable {
  /** A stable implementation name for diagnostics, when the backend declares one. */
  public val id: String?
    get() = null

  /** Whether this backend has a usable platform implementation, stable for its lifetime. */
  public val availability: LocationBackendAvailability
    get() = LocationBackendAvailability.Available

  /**
   * Current foreground location permission.
   *
   * This value also reflects changes made outside the application when the platform reports them.
   *
   * The default is always [LocationPermission.Granted] with a `null` accuracy authorization. A
   * source where permission is not a concept, such as an external receiver or a network feed, keeps
   * the default and needs no permission handling.
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
   * [LocationProvider] calls this only when [availability] is
   * [LocationBackendAvailability.Available]. Implement platform requests here; the provider owns
   * unsupported and misconfigured availability handling.
   *
   * Each collector starts an independent platform location request. Cancelling collection stops
   * that request and unregisters its callbacks.
   */
  public fun updates(request: LocationRequest): Flow<LocationEvent>

  /**
   * Releases resources owned by this backend. Repeated calls have no effect.
   *
   * Cancel update collectors when disposing the provider. Implementations that own permission
   * observers or platform clients release them here. Android, iOS, and macOS providers require the
   * main thread. The default does nothing.
   */
  override fun close(): Unit = Unit
}

private val AlwaysGrantedLocationPermission: StateFlow<LocationPermission> =
  MutableStateFlow(LocationPermission.Granted(accuracy = null))
