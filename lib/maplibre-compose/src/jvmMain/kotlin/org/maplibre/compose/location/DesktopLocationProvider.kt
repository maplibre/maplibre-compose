package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.desktop.LocalComposeMapHostOrNull

/**
 * A host-specific desktop location implementation discovered through [ServiceLoader].
 *
 * No installed or available backend maps to [LocationUnavailableReason.Unsupported]. Multiple
 * installed backends, a [ServiceConfigurationError], or an exception from [createProvider] maps to
 * [LocationUnavailableReason.Misconfigured]. The selected backend documents its remaining mappings.
 */
public interface DesktopLocationBackend {
  /** A stable name used in diagnostics. */
  public val id: String

  /** Whether this backend can run on the current operating system and architecture. */
  public fun isAvailable(): Boolean

  /** Creates a location provider for [host]. */
  public fun createProvider(host: ComposeMapHost?): DesktopLocationProvider

  /** Creates a foreground permission requester and parents its UI to [host] when supported. */
  public fun createPermissionRequester(host: ComposeMapHost?): DesktopLocationPermissionRequester
}

/** A desktop provider whose process resources can be released with [close]. */
public interface DesktopLocationProvider : LocationProvider, AutoCloseable

/** A desktop permission requester whose process resources can be released with [close]. */
public interface DesktopLocationPermissionRequester : LocationPermissionRequester, AutoCloseable

@Composable
public actual fun rememberDefaultLocationProvider(): LocationProvider {
  val host = LocalComposeMapHostOrNull.current
  val provider =
    remember(host) {
      DesktopLocationBackendResolver.discover(host)
    }
  DisposableEffect(provider) { onDispose { provider.close() } }
  return provider
}

@Composable
public actual fun rememberDefaultLocationPermissionRequester(): LocationPermissionRequester {
  val host = LocalComposeMapHostOrNull.current
  val requester =
    remember(host) {
      DesktopLocationBackendResolver.discoverPermissionRequester(host)
    }
  DisposableEffect(requester) { onDispose { requester.close() } }
  return requester
}

internal object DesktopLocationBackendResolver {
  fun discover(
    host: ComposeMapHost? = null,
    loadBackends: () -> List<DesktopLocationBackend> = {
      ServiceLoader.load(DesktopLocationBackend::class.java).toList()
    },
  ): DesktopLocationProvider =
    try {
      resolve(loadBackends(), host)
    } catch (error: ServiceConfigurationError) {
      UnavailableDesktopLocationProvider(LocationBackendAvailability.Misconfigured(error))
    }

  fun discoverPermissionRequester(
    host: ComposeMapHost? = null,
    loadBackends: () -> List<DesktopLocationBackend> = {
      ServiceLoader.load(DesktopLocationBackend::class.java).toList()
    },
  ): DesktopLocationPermissionRequester =
    try {
      resolvePermissionRequester(loadBackends(), host)
    } catch (error: ServiceConfigurationError) {
      UnavailableDesktopLocationPermissionRequester(
        LocationBackendAvailability.Misconfigured(error)
      )
    }

  fun resolve(
    backends: List<DesktopLocationBackend>,
    host: ComposeMapHost? = null,
  ): DesktopLocationProvider =
    when {
      backends.isEmpty() ->
        UnavailableDesktopLocationProvider(LocationBackendAvailability.Unsupported)
      backends.size > 1 ->
        UnavailableDesktopLocationProvider(
          LocationBackendAvailability.Misconfigured(
            IllegalStateException(
              "Multiple desktop location backends are installed: " + backends.joinToString { it.id }
            )
          )
        )
      !backends.single().isAvailable() ->
        UnavailableDesktopLocationProvider(LocationBackendAvailability.Unsupported)
      else ->
        try {
          backends.single().createProvider(host)
        } catch (error: Throwable) {
          UnavailableDesktopLocationProvider(LocationBackendAvailability.Misconfigured(error))
        }
    }

  fun resolvePermissionRequester(
    backends: List<DesktopLocationBackend>,
    host: ComposeMapHost? = null,
  ): DesktopLocationPermissionRequester =
    when {
      backends.isEmpty() || (backends.size == 1 && !backends.single().isAvailable()) ->
        UnavailableDesktopLocationPermissionRequester(LocationBackendAvailability.Unsupported)
      backends.size > 1 ->
        UnavailableDesktopLocationPermissionRequester(
          LocationBackendAvailability.Misconfigured(
            IllegalStateException(
              "Multiple desktop location backends are installed: " + backends.joinToString { it.id }
            )
          )
        )
      else ->
        try {
          backends.single().createPermissionRequester(host)
        } catch (error: Throwable) {
          UnavailableDesktopLocationPermissionRequester(
            LocationBackendAvailability.Misconfigured(error)
          )
        }
    }
}

private class UnavailableDesktopLocationProvider(
  override val backendAvailability: LocationBackendAvailability
) : DesktopLocationProvider {
  override fun updates(request: LocationRequest): Flow<LocationEvent> =
    flowOf(
      when (val availability = backendAvailability) {
        LocationBackendAvailability.Available ->
          error("An unavailable provider cannot report an available backend")
        is LocationBackendAvailability.Misconfigured ->
          LocationEvent.Unavailable(
            LocationUnavailableReason.Misconfigured,
            availability.cause,
          )
        LocationBackendAvailability.Unsupported ->
          LocationEvent.Unavailable(LocationUnavailableReason.Unsupported)
      }
    )

  override fun close() = Unit
}

private class UnavailableDesktopLocationPermissionRequester(
  override val backendAvailability: LocationBackendAvailability
) : DesktopLocationPermissionRequester {
  override val status: StateFlow<LocationPermission> =
    MutableStateFlow(LocationPermission.NotGranted(canRequest = null))

  override fun requestForegroundPermission() = Unit

  override fun close() = Unit
}
