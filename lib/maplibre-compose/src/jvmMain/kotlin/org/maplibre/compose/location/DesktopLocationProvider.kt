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
      UnavailableDesktopLocationProvider(LocationUnavailableReason.Misconfigured, error)
    }

  fun discoverPermissionRequester(
    host: ComposeMapHost? = null,
    loadBackends: () -> List<DesktopLocationBackend> = {
      ServiceLoader.load(DesktopLocationBackend::class.java).toList()
    },
  ): DesktopLocationPermissionRequester =
    try {
      resolvePermissionRequester(loadBackends(), host)
    } catch (_: ServiceConfigurationError) {
      FixedDesktopLocationPermissionRequester(LocationUnavailableReason.Misconfigured)
    }

  fun resolve(
    backends: List<DesktopLocationBackend>,
    host: ComposeMapHost? = null,
  ): DesktopLocationProvider =
    when {
      backends.isEmpty() ->
        UnavailableDesktopLocationProvider(LocationUnavailableReason.Unsupported)
      backends.size > 1 ->
        UnavailableDesktopLocationProvider(
          reason = LocationUnavailableReason.Misconfigured,
          cause =
            IllegalStateException(
              "Multiple desktop location backends are installed: " + backends.joinToString { it.id }
            ),
        )
      !backends.single().isAvailable() ->
        UnavailableDesktopLocationProvider(LocationUnavailableReason.Unsupported)
      else ->
        try {
          backends.single().createProvider(host)
        } catch (error: Throwable) {
          UnavailableDesktopLocationProvider(LocationUnavailableReason.Misconfigured, error)
        }
    }

  fun resolvePermissionRequester(
    backends: List<DesktopLocationBackend>,
    host: ComposeMapHost? = null,
  ): DesktopLocationPermissionRequester =
    when {
      backends.isEmpty() || (backends.size == 1 && !backends.single().isAvailable()) ->
        FixedDesktopLocationPermissionRequester(LocationUnavailableReason.Unsupported)
      backends.size > 1 ->
        FixedDesktopLocationPermissionRequester(LocationUnavailableReason.Misconfigured)
      else ->
        try {
          backends.single().createPermissionRequester(host)
        } catch (_: Throwable) {
          FixedDesktopLocationPermissionRequester(LocationUnavailableReason.Misconfigured)
        }
    }
}

private class UnavailableDesktopLocationProvider(
  private val reason: LocationUnavailableReason,
  private val cause: Throwable? = null,
) : DesktopLocationProvider {
  override val isSupported: Boolean = reason != LocationUnavailableReason.Unsupported

  override fun updates(request: LocationRequest): Flow<LocationEvent> =
    flowOf(LocationEvent.Unavailable(reason, cause))

  override fun close() = Unit
}

private class FixedDesktopLocationPermissionRequester(reason: LocationUnavailableReason) :
  DesktopLocationPermissionRequester {
  override val status: StateFlow<LocationPermission> =
    MutableStateFlow(
      if (reason == LocationUnavailableReason.Unsupported) {
        LocationPermission.NotGranted(canRequest = null)
      } else {
        LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
      }
    )

  override fun requestForegroundPermission() = Unit

  override fun close() = Unit
}
