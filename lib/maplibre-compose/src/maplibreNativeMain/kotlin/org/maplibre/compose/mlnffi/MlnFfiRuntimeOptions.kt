package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import kotlinx.io.files.Path
import org.maplibre.compose.map.ProcessNativeMapRuntime
import org.maplibre.compose.resource.MlnFfiResourceProvider
import org.maplibre.compose.resource.MlnFfiResourceProviderFactory

/** Platform-resolved configuration for a MapLibre Native FFI runtime. */
@Immutable
internal data class MlnFfiRuntimeOptions(
  val cacheFile: Path,
  val maximumCacheSizeBytes: Long? = null,
  val logger: Logger? = Logger.withTag("maplibre-compose"),
  internal val resourceProviderFactory: MlnFfiResourceProviderFactory = ::MlnFfiResourceProvider,
)

/** Uses one stable lexical identity for a cache database without requiring it to exist yet. */
internal fun MlnFfiRuntimeOptions.normalized(): MlnFfiRuntimeOptions {
  val normalizedFile = normalizeMlnFfiPath(cacheFile)
  return if (normalizedFile == cacheFile) this else copy(cacheFile = normalizedFile)
}

/** The one process-wide MapLibre Native configuration. */
internal object MlnFfiApplication {
  private val lock = MlnFfiLock()

  @Volatile private var optionsState: MlnFfiRuntimeOptions? = null

  fun configure(rawOptions: MlnFfiRuntimeOptions) {
    val options = rawOptions.normalized()
    lock.withLock {
      val existing = optionsState
      if (existing != null) {
        check(existing == options) {
          "MapLibre is already configured with ${existing.describe()}, not ${options.describe()}"
        }
        return
      }

      optionsState = options
    }
  }

  val isConfigured: Boolean
    get() = optionsState != null

  /** Installs [defaultOptions] when no configuration is set. */
  fun ensureConfigured(defaultOptions: () -> MlnFfiRuntimeOptions) {
    if (isConfigured) return
    lock.withLock {
      if (optionsState != null) return
      optionsState = defaultOptions().normalized()
    }
  }

  val options: MlnFfiRuntimeOptions
    get() = requireOptions()

  private fun requireOptions(): MlnFfiRuntimeOptions =
    checkNotNull(optionsState) {
      "MapLibre is not configured. Compose a map or call MapLibre.configure(...) first."
    }

  /** Forgets the process-wide configuration and closes its runtime. Tests only. */
  internal fun resetForTest(): Boolean {
    lock.withLock { optionsState = null }
    return ProcessNativeMapRuntime.resetForTest()
  }
}

private fun MlnFfiRuntimeOptions.describe(): String =
  "cacheFile='$cacheFile', maximumCacheSizeBytes=$maximumCacheSizeBytes"
