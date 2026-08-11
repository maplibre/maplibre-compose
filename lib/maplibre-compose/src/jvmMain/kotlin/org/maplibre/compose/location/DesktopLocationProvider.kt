package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import kotlinx.coroutines.flow.Flow
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

  /** Creates a provider and parents its permission UI to [host] when the platform supports it. */
  public fun createProvider(host: ComposeMapHost?): DesktopLocationProvider
}

/** A desktop provider whose process resources can be released with [close]. */
public interface DesktopLocationProvider : LocationProvider, AutoCloseable

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
}

private class UnavailableDesktopLocationProvider(
  private val reason: LocationUnavailableReason,
  private val cause: Throwable? = null,
) : DesktopLocationProvider {
  override val isSupported: Boolean = reason != LocationUnavailableReason.Unsupported
  override val permission: LocationPermissionController =
    FixedLocationPermissionController(
      if (reason == LocationUnavailableReason.Unsupported) {
        LocationPermission.NotGranted(canRequest = null)
      } else {
        LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
      }
    )

  override fun updates(request: LocationRequest): Flow<LocationEvent> =
    flowOf(LocationEvent.Unavailable(reason, cause))

  override fun close() = Unit
}
