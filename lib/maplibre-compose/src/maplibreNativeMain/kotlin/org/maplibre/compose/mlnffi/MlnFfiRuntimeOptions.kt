package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Immutable
import kotlinx.io.files.Path
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.resource.MlnFfiResourceProvider
import org.maplibre.compose.resource.MlnFfiResourceProviderFactory

/** Platform-resolved configuration for a MapLibre Native FFI runtime. */
@Immutable
internal data class MlnFfiRuntimeOptions(
  val cacheFile: Path,
  val maximumCacheSizeBytes: Long? = null,
  val requestInterceptor: MapRequestInterceptor? = null,
  val resourceProvider: MapResourceProvider? = null,
  val logger: MapLog? = MapLog,
  internal val resourceProviderFactory: MlnFfiResourceProviderFactory = ::MlnFfiResourceProvider,
)

/** Uses one stable lexical identity for a cache database without requiring it to exist yet. */
internal fun MlnFfiRuntimeOptions.normalized(): MlnFfiRuntimeOptions {
  val normalizedFile = normalizeMlnFfiPath(cacheFile)
  return if (normalizedFile == cacheFile) this else copy(cacheFile = normalizedFile)
}
