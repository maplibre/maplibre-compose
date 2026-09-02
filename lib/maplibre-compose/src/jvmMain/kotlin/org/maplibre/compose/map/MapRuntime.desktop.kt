package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import org.maplibre.compose.desktop.desktopRuntimeOptions
import org.maplibre.compose.desktop.inferredApplicationId
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceProvider

/** Configuration for one desktop map runtime. */
public actual data class MapRuntimeOptions(
  /** Reverse-domain name for the runtime's cache directory. */
  public val applicationId: String = inferredApplicationId(),
  /** Ambient cache size in bytes, or null for MapLibre's default. */
  public val maximumCacheSizeBytes: Long? = null,
  /** Receives diagnostic messages from maps and shared runtime resources. */
  public val logger: Logger? = Logger.withTag("maplibre-compose"),
  /** Rewrites URLs and headers for every resource this runtime fetches. */
  public val requestInterceptor: MapRequestInterceptor? = null,
  /** Serves bytes for resource URLs this provider accepts. */
  public val resourceProvider: MapResourceProvider? = null,
)

internal fun MapRuntimeOptions.toMlnFfiRuntimeOptions() =
  desktopRuntimeOptions(
    applicationId,
    maximumCacheSizeBytes,
    logger,
    requestInterceptor,
    resourceProvider,
  )

public actual fun createMapRuntime(options: MapRuntimeOptions): MapRuntime =
  createNativeMapRuntime(options.toMlnFfiRuntimeOptions())

@Composable
public actual fun rememberDefaultMapRuntime(): MapRuntime = DefaultMapRuntime.getOrCreate {
  MapRuntimeOptions().toMlnFfiRuntimeOptions()
}
