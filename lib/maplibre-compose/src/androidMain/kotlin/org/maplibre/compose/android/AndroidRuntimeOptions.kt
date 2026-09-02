package org.maplibre.compose.android

import android.content.Context
import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import java.io.File
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceProvider

/** Configuration for one MapLibre Native runtime on Android. */
@Immutable
public data class AndroidRuntimeOptions(
  /** Context used to initialize Android platform services; only its application is retained. */
  public val context: Context,

  /** Where the ambient resource cache and offline-region database live. */
  public val cacheFile: File = androidCacheFile(context),

  /** Maximum ambient cache size in bytes, or null for MapLibre's own default. */
  public val maximumCacheSizeBytes: Long? = null,

  /** Receives diagnostic messages from maps and shared runtime resources. */
  public val logger: Logger? = Logger.withTag("maplibre-compose"),

  /** Rewrites URLs and headers for every resource this runtime fetches. */
  public val requestInterceptor: MapRequestInterceptor? = null,

  /** Serves bytes for resource URLs this provider accepts. */
  public val resourceProvider: MapResourceProvider? = null,
)

/** Returns the default private cache database for this application. */
public fun androidCacheFile(context: Context): File =
  context.applicationContext.cacheDir.resolve("maplibre-cache.db")

internal fun AndroidRuntimeOptions.toMlnFfiRuntimeOptions(): MlnFfiRuntimeOptions =
  MlnFfiRuntimeOptions(
    Path(cacheFile.absolutePath),
    maximumCacheSizeBytes,
    logger,
    requestInterceptor,
    resourceProvider,
  )
