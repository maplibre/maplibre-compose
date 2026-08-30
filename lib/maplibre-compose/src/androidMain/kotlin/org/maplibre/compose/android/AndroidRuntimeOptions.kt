package org.maplibre.compose.android

import android.content.Context
import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import java.io.File
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions

/** Configuration for one MapLibre Native runtime on Android. */
@Immutable
public data class AndroidRuntimeOptions(
  /** Where the ambient resource cache and offline-region database live. */
  public val cacheFile: File,

  /** Maximum ambient cache size in bytes, or null for MapLibre's own default. */
  public val maximumCacheSizeBytes: Long? = null,

  /** Receives diagnostic messages from maps and shared runtime resources. */
  public val logger: Logger? = Logger.withTag("maplibre-compose"),
)

/** Returns the default private cache database for this application. */
public fun androidCacheFile(context: Context): File =
  context.applicationContext.cacheDir.resolve("maplibre-cache.db")

internal fun AndroidRuntimeOptions.toMlnFfiRuntimeOptions(): MlnFfiRuntimeOptions =
  MlnFfiRuntimeOptions(Path(cacheFile.absolutePath), maximumCacheSizeBytes, logger)
