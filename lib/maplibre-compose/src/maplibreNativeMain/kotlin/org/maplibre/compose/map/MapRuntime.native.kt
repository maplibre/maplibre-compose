package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceProvider

/** Configuration for one MapLibre Native map runtime. */
@Immutable
public actual data class MapRuntimeOptions(
  /**
   * The path of the ambient resource cache and offline-region database. Null selects a
   * `maplibre-cache.db` file in the platform's cache directory for this application.
   */
  public val cacheFile: Path? = null,
  /** Maximum ambient cache size in bytes, or null for MapLibre's own default. */
  public val maximumCacheSizeBytes: Long? = null,
  /** Rewrites URLs and headers for this runtime. Fixed at construction. */
  public val requestInterceptor: MapRequestInterceptor? = null,
  /** Serves bytes for resource URLs this provider accepts. Fixed at construction. */
  public val resourceProvider: MapResourceProvider? = null,
)

internal actual fun defaultMapRuntimeOptions(): MapRuntimeOptions = MapRuntimeOptions()

/** The cache file that a null [MapRuntimeOptions.cacheFile] selects. */
internal expect fun defaultCacheFile(): Path

/** Initializes the platform services that MapLibre Native requires, before the first runtime. */
internal expect fun initializeNativePlatform()

internal fun MapRuntimeOptions.toMlnFfiRuntimeOptions(): MlnFfiRuntimeOptions =
  MlnFfiRuntimeOptions(
    cacheFile = cacheFile ?: defaultCacheFile(),
    maximumCacheSizeBytes = maximumCacheSizeBytes,
    requestInterceptor = requestInterceptor,
    resourceProvider = resourceProvider,
  )

public actual fun createMapRuntime(options: MapRuntimeOptions): MapRuntime {
  initializeNativePlatform()
  return createNativeMapRuntime(options.toMlnFfiRuntimeOptions())
}
