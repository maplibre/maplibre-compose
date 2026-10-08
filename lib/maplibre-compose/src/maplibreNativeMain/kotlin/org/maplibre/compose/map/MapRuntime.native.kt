@file:OptIn(org.maplibre.compose.util.ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

@Immutable
public actual data class MapRuntimeOptions
internal constructor(
  /**
   * The ambient resource cache and offline-region database path. Null selects a `maplibre-cache.db`
   * file in the platform's cache directory for this application, resolved when the runtime is
   * created.
   */
  // Exposes kotlinx-io Path, which is not yet stable.
  @ExperimentalMaplibreComposeApi public val cacheFile: Path?,
  /** Maximum ambient cache size in bytes. Defaults to 50 MiB, the MapLibre Native default. */
  public val maximumCacheSizeBytes: Long,
  public actual val requestInterceptor: MapRequestInterceptor?,
  public actual val resourceProvider: MapResourceProvider?,
  public actual val mainDispatcher: CoroutineDispatcher,
) {
  public actual constructor(
    from: MapRuntimeOptions,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(
    builder.cacheFile,
    builder.maximumCacheSizeBytes,
    builder.requestInterceptor,
    builder.resourceProvider,
    builder.mainDispatcher,
  )

  @MapOptionsDsl
  public actual class Builder internal constructor(from: MapRuntimeOptions) {
    /** See [MapRuntimeOptions.cacheFile]. */
    // Exposes kotlinx-io Path, which is not yet stable.
    @ExperimentalMaplibreComposeApi public var cacheFile: Path? = from.cacheFile

    /** See [MapRuntimeOptions.maximumCacheSizeBytes]. */
    public var maximumCacheSizeBytes: Long = from.maximumCacheSizeBytes

    public actual var requestInterceptor: MapRequestInterceptor? = from.requestInterceptor
    public actual var resourceProvider: MapResourceProvider? = from.resourceProvider
    public actual var mainDispatcher: CoroutineDispatcher = from.mainDispatcher
  }

  public actual companion object {
    public actual val Standard: MapRuntimeOptions =
      MapRuntimeOptions(
        cacheFile = null,
        maximumCacheSizeBytes = DefaultMaximumCacheSizeBytes,
        requestInterceptor = null,
        resourceProvider = null,
        mainDispatcher = Dispatchers.Main,
      )
  }
}

// MapLibre Native util::DEFAULT_MAX_CACHE_SIZE.
private const val DefaultMaximumCacheSizeBytes: Long = 50L * 1024 * 1024

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
    mainDispatcher =
      if (
        mainDispatcher === MapRuntimeOptions.Standard.mainDispatcher ||
          mainDispatcher === Dispatchers.Main
      )
        platformMainDispatcher()
      else mainDispatcher,
  )

public actual fun createMapRuntime(
  from: MapRuntimeOptions,
  block: MapRuntimeOptions.Builder.() -> Unit,
): MapRuntime {
  val options = MapRuntimeOptions(from, block)
  initializeNativePlatform()
  return createNativeMapRuntime(options.toMlnFfiRuntimeOptions())
}
