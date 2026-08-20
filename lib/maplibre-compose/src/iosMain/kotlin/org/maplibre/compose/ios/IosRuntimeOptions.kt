package org.maplibre.compose.ios

import androidx.compose.runtime.Immutable
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/** Process-wide configuration for MapLibre Native on iOS. */
@Immutable
public data class IosRuntimeOptions(
  /** Where the ambient resource cache and offline-region database live. */
  public val cacheFile: String,

  /** Maximum ambient cache size in bytes, or null for MapLibre's own default. */
  public val maximumCacheSizeBytes: Long? = null,
)

/** Returns the default private cache database for this application. */
public fun iosCacheFile(): String {
  val caches =
    NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, expandTilde = true)
      .first() as String
  return "$caches/maplibre-cache.db"
}

internal fun IosRuntimeOptions.toMlnFfiRuntimeOptions(): MlnFfiRuntimeOptions =
  MlnFfiRuntimeOptions(Path(cacheFile), maximumCacheSizeBytes)
