package org.maplibre.compose.location

import android.content.Context
import androidx.compose.runtime.Composable
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * An Android location implementation discovered through [ServiceLoader].
 *
 * An installed and available backend supplies the providers that [rememberDefaultLocationProvider]
 * and [rememberDefaultOrientationProvider] create; the framework providers remain the default
 * otherwise. When several backends are available, the resolver selects the highest [priority], and
 * the lexicographically first [id] if those values match. A [ServiceConfigurationError] or an
 * exception while checking a backend maps [LocationProvider.backendAvailability] to
 * [LocationBackendAvailability.Misconfigured]. The installed backend documents its own availability
 * conditions.
 */
public interface AndroidLocationBackend {
  /** A stable name used in diagnostics and as the tie-break when [priority] values match. */
  public val id: String

  /**
   * Relative preference among available backends. Higher values win.
   *
   * First-party backends use values from 0 through 100. A third-party backend can outrank them with
   * a higher value. The resolver breaks a priority tie with the lexicographically first [id].
   */
  public val priority: Int
    get() = 0

  /** Whether this backend can run on the current device. */
  public fun isAvailable(context: Context): Boolean

  /** Creates and remembers this backend's location provider. */
  @Composable public fun rememberLocationProvider(): LocationProvider

  /**
   * Creates and remembers this backend's orientation provider, or returns null so the default
   * framework orientation provider is used.
   */
  @Composable
  public fun rememberOrientationProvider(updateInterval: Duration): OrientationProvider? = null
}

internal sealed interface AndroidBackendResolution {
  data class Discovered(val backend: AndroidLocationBackend) : AndroidBackendResolution

  data class Misconfigured(val cause: Throwable) : AndroidBackendResolution

  data object None : AndroidBackendResolution
}

internal object AndroidLocationBackendResolver {
  fun discover(
    context: Context,
    loadBackends: () -> List<AndroidLocationBackend> = {
      ServiceLoader.load(AndroidLocationBackend::class.java).toList()
    },
  ): AndroidBackendResolution {
    val availableBackends =
      try {
        loadBackends().filter { it.isAvailable(context) }
      } catch (error: ServiceConfigurationError) {
        return AndroidBackendResolution.Misconfigured(error)
      } catch (error: Exception) {
        return AndroidBackendResolution.Misconfigured(error)
      }
    return resolve(availableBackends)
  }

  fun resolve(availableBackends: List<AndroidLocationBackend>): AndroidBackendResolution {
    val backend =
      availableBackends.minWithOrNull(
        compareByDescending<AndroidLocationBackend> { it.priority }.thenBy { it.id }
      ) ?: return AndroidBackendResolution.None
    return AndroidBackendResolution.Discovered(backend)
  }
}

internal class MisconfiguredLocationProvider(private val cause: Throwable) : LocationProvider {
  override val backendAvailability: LocationBackendAvailability =
    LocationBackendAvailability.Misconfigured(cause)

  override fun updates(request: LocationRequest): Flow<LocationEvent> =
    flowOf(LocationEvent.Unavailable(LocationUnavailableReason.Misconfigured, cause))
}
