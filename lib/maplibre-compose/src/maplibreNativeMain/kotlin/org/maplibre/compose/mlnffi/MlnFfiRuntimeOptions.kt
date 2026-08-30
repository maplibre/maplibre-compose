package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import kotlinx.io.files.Path
import org.maplibre.compose.offline.MlnFfiOfflineManager
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

/** The one process-wide MapLibre Native configuration and the runtime that owns its cache. */
internal object MlnFfiApplication {
  private class State(val options: MlnFfiRuntimeOptions, val offlineManager: MlnFfiOfflineManager)

  private val lock = MlnFfiLock()

  @Volatile private var state: State? = null

  fun configure(rawOptions: MlnFfiRuntimeOptions) {
    val options = rawOptions.normalized()
    lock.withLock {
      val existing = state
      if (existing != null) {
        check(existing.options == options) {
          "MapLibre is already configured with ${existing.options.describe()}, not ${options.describe()}"
        }
        return
      }

      state = State(options, MlnFfiOfflineManager(options))
    }
  }

  val isConfigured: Boolean
    get() = state != null

  /** Installs [defaultOptions] when no configuration is set. */
  fun ensureConfigured(defaultOptions: () -> MlnFfiRuntimeOptions) {
    if (isConfigured) return
    lock.withLock {
      if (state != null) return
      val options = defaultOptions().normalized()
      state = State(options, MlnFfiOfflineManager(options))
    }
  }

  val options: MlnFfiRuntimeOptions
    get() = requireState().options

  val offlineManager: MlnFfiOfflineManager
    get() = requireState().offlineManager

  private fun requireState(): State =
    checkNotNull(state) {
      "MapLibre is not configured. Compose a map, call rememberOfflineManager, or call " +
        "MapLibre.configure(...) first."
    }

  /**
   * Stops and forgets the process-wide owner. Tests only; production configuration is permanent.
   */
  internal fun resetForTest(): Boolean {
    val previous = lock.withLock { state.also { state = null } } ?: return true
    return previous.offlineManager.closeForTest()
  }
}

private fun MlnFfiRuntimeOptions.describe(): String =
  "cacheFile='$cacheFile', maximumCacheSizeBytes=$maximumCacheSizeBytes"
