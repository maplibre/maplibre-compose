package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import kotlinx.io.files.Path
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.resource.MlnFfiResourceProvider
import org.maplibre.compose.resource.MlnFfiResourceProviderFactory

/** Platform-resolved configuration for a MapLibre Native FFI runtime. */
@Immutable
internal data class MlnFfiRuntimeOptions(
  val cacheFile: Path,
  val maximumCacheSizeBytes: Long? = null,
  val logger: Logger? = Logger.withTag("maplibre-compose"),
  val requestInterceptor: MapRequestInterceptor? = null,
  val resourceProvider: MapResourceProvider? = null,
  internal val resourceProviderFactory: MlnFfiResourceProviderFactory = ::MlnFfiResourceProvider,
)

/** Uses one stable lexical identity for a cache database without requiring it to exist yet. */
internal fun MlnFfiRuntimeOptions.normalized(): MlnFfiRuntimeOptions {
  val normalizedFile = normalizeMlnFfiPath(cacheFile)
  return if (normalizedFile == cacheFile) this else copy(cacheFile = normalizedFile)
}
