package org.maplibre.compose.location

import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * A host-specific desktop location implementation discovered through [ServiceLoader].
 *
 * A backend installs one provider that carries both location updates and permission. No installed
 * or available backend maps [LocationProvider.backendAvailability] to
 * [LocationBackendAvailability.Unsupported]. Multiple available backends, a
 * [ServiceConfigurationError], or an exception while checking or creating a backend maps the
 * property to [LocationBackendAvailability.Misconfigured]. The selected backend documents its
 * location-event and permission mappings.
 */
public interface DesktopLocationBackend {
  /** A stable name used in diagnostics. */
  public val id: String

  /** Whether this backend can run on the current operating system and architecture. */
  public fun isAvailable(): Boolean

  /** Creates a location provider whose system dialogs are parented to [window]. */
  public fun createProvider(window: XdgPortalWindow?): LocationProvider
}

/**
 * Creates the default desktop location provider from the installed backend. The provider's system
 * dialogs are parented to [window], and [LocationProvider.close] releases its process resources.
 */
public fun createDefaultLocationProvider(window: XdgPortalWindow? = null): LocationProvider =
  DesktopLocationBackendResolver.discover(window)

internal object DesktopLocationBackendResolver {
  fun discover(
    window: XdgPortalWindow? = null,
    loadBackends: () -> List<DesktopLocationBackend> = {
      ServiceLoader.load(DesktopLocationBackend::class.java).toList()
    },
  ): LocationProvider =
    try {
      resolve(loadBackends(), window)
    } catch (error: ServiceConfigurationError) {
      UnavailableLocationProvider(LocationBackendAvailability.Misconfigured(error))
    }

  fun resolve(
    backends: List<DesktopLocationBackend>,
    window: XdgPortalWindow? = null,
  ): LocationProvider {
    val availableBackends =
      try {
        backends.filter { it.isAvailable() }
      } catch (error: Throwable) {
        return UnavailableLocationProvider(LocationBackendAvailability.Misconfigured(error))
      }
    return when {
      availableBackends.isEmpty() -> UnsupportedLocationProvider
      availableBackends.size > 1 ->
        UnavailableLocationProvider(
          LocationBackendAvailability.Misconfigured(
            IllegalStateException(
              "Multiple desktop location backends are available: " +
                availableBackends.joinToString { it.id }
            )
          )
        )
      else ->
        try {
          availableBackends.single().createProvider(window)
        } catch (error: Throwable) {
          UnavailableLocationProvider(LocationBackendAvailability.Misconfigured(error))
        }
    }
  }
}
