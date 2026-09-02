package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import kotlinx.io.files.Path
import org.maplibre.compose.ios.iosCacheFile
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceProvider

/** Configuration for one iOS map runtime. */
@Immutable
public actual data class MapRuntimeOptions(
  /** Where the ambient resource cache and offline-region database live. */
  public val cacheFile: String = iosCacheFile(),
  /** Maximum ambient cache size in bytes, or null for MapLibre's own default. */
  public val maximumCacheSizeBytes: Long? = null,
  /** Receives diagnostic messages from maps and shared runtime resources. */
  public val logger: Logger? = Logger.withTag("maplibre-compose"),
  /** Rewrites URLs and headers for every resource this runtime fetches. */
  public val requestInterceptor: MapRequestInterceptor? = null,
  /** Serves bytes for resource URLs this provider accepts. */
  public val resourceProvider: MapResourceProvider? = null,
)

internal fun MapRuntimeOptions.toMlnFfiRuntimeOptions(): MlnFfiRuntimeOptions =
  MlnFfiRuntimeOptions(
    Path(cacheFile),
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
