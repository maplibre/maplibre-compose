package org.maplibre.compose.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger
import java.io.File
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceProvider

/** Configuration for one Android map runtime. */
@Immutable
public actual data class MapRuntimeOptions(
  /** Context used to initialize Android platform services; only its application is retained. */
  public val context: Context,
  /** Where the ambient resource cache and offline-region database live. */
  public val cacheFile: File = context.applicationContext.cacheDir.resolve("maplibre-cache.db"),
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
    Path(cacheFile.absolutePath),
    maximumCacheSizeBytes,
    logger,
    requestInterceptor,
    resourceProvider,
  )

public actual fun createMapRuntime(options: MapRuntimeOptions): MapRuntime {
  AndroidMlnFfiPlatform.initialize(options.context)
  return createNativeMapRuntime(options.toMlnFfiRuntimeOptions())
}

@Composable
public actual fun rememberDefaultMapRuntime(): MapRuntime {
  val context = LocalContext.current
  AndroidMlnFfiPlatform.initialize(context)
  return DefaultMapRuntime.getOrCreate {
    MapRuntimeOptions(context).toMlnFfiRuntimeOptions()
  }
}
