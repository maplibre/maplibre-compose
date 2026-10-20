package org.maplibre.compose.location

import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * A factory for host-specific desktop location backends, discovered through [ServiceLoader].
 *
 * Each factory creates a backend that carries both location updates and permission. No installed or
 * available backend maps [LocationProvider.backendAvailability] to
 * [LocationBackendAvailability.Unsupported]. Multiple available backends, a
 * [ServiceConfigurationError], or an exception while checking or creating a backend maps the
 * property to [LocationBackendAvailability.Misconfigured]. The selected backend documents its
 * location-event and permission mappings.
 */
public interface DesktopLocationBackendFactory {
  /** A stable name used in diagnostics. */
  public val id: String

  /** Whether this backend can run on the current operating system and architecture. */
  public fun isAvailable(): Boolean

  /** Creates a location backend whose system dialogs are parented to [window]. */
  public fun createBackend(window: XdgPortalWindow?): LocationBackend
}

/**
 * Creates the default desktop location provider from the installed backend. The provider's system
 * dialogs are parented to [window], and [LocationProvider.close] releases its process resources.
 */
public fun createDefaultLocationProvider(window: XdgPortalWindow? = null): LocationProvider =
  LocationProvider(DesktopLocationBackendResolver.discover(window))

internal object DesktopLocationBackendResolver {
  fun discover(
    window: XdgPortalWindow? = null,
    loadBackends: () -> List<DesktopLocationBackendFactory> = {
      ServiceLoader.load(DesktopLocationBackendFactory::class.java).toList()
    },
  ): LocationBackend =
    try {
      resolve(loadBackends(), window)
    } catch (error: ServiceConfigurationError) {
      UnavailableLocationBackend(LocationBackendAvailability.Misconfigured(error))
    }

  fun resolve(
    backends: List<DesktopLocationBackendFactory>,
    window: XdgPortalWindow? = null,
  ): LocationBackend {
    val availableBackends =
      try {
        backends.filter { it.isAvailable() }
      } catch (error: Throwable) {
        return UnavailableLocationBackend(LocationBackendAvailability.Misconfigured(error))
      }
    return when {
      availableBackends.isEmpty() ->
        UnavailableLocationBackend(LocationBackendAvailability.Unsupported)
      availableBackends.size > 1 ->
        UnavailableLocationBackend(
          LocationBackendAvailability.Misconfigured(
            IllegalStateException(
              "Multiple desktop location backends are available: " +
                availableBackends.joinToString { it.id },
            ),
          ),
        )
      else ->
        try {
          availableBackends.single().createBackend(window)
        } catch (error: Throwable) {
          UnavailableLocationBackend(LocationBackendAvailability.Misconfigured(error))
        }
    }
  }
}
