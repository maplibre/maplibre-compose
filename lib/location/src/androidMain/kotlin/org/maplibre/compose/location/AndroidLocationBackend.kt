package org.maplibre.compose.location

import android.content.Context
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * An Android location implementation discovered through [ServiceLoader].
 *
 * An installed and available backend supplies the providers that [createDefaultLocationProvider]
 * and [createDefaultHeadingProvider] create; the framework providers remain the default otherwise.
 * When several backends are available, the highest [priority] wins, and equal priorities resolve to
 * the first [id] in lexicographic order. A [ServiceConfigurationError], a [LinkageError], or an
 * exception while loading or checking a backend maps [LocationProvider.availability] to
 * [LocationProviderAvailability.Misconfigured]. The installed backend documents its own
 * availability conditions.
 */
public interface AndroidLocationBackend {
  /** A stable name used in diagnostics and to break priority ties. */
  public val id: String

  /**
   * The precedence of this backend when several are available. The backend with the highest
   * priority supplies the default providers. Backends ship with priority 0; declare a higher value
   * for a backend that is a better fit on its own hardware, or a negative value for a last resort.
   */
  public val priority: Int
    get() = 0

  /** Whether this backend can run on the current device. */
  public fun isAvailable(context: Context): Boolean

  /** Creates this backend's location provider. */
  public fun createLocationProvider(context: Context): LocationProvider

  /**
   * Creates this backend's heading provider, or returns null so the default framework heading
   * provider is used.
   */
  public fun createHeadingProvider(context: Context): HeadingProvider? = null
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
      } catch (error: LinkageError) {
        // A backend packaged without its vendor SDK fails with NoClassDefFoundError.
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
      )
    return if (backend == null) AndroidBackendResolution.None
    else AndroidBackendResolution.Discovered(backend)
  }
}

internal class MisconfiguredLocationProvider(private val cause: Throwable) : LocationProvider {
  override val availability: LocationProviderAvailability =
    LocationProviderAvailability.Misconfigured(cause)

  override fun updates(request: LocationRequest): Flow<LocationEvent> =
    flowOf(LocationEvent.Unavailable(LocationUnavailableReason.Misconfigured, cause))
}
