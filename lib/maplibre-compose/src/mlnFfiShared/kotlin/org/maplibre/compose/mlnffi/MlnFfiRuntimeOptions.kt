package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Immutable
import java.io.File
import org.maplibre.compose.offline.MlnFfiOfflineManager

/** Platform-resolved configuration for a MapLibre Native FFI runtime. */
@Immutable
internal data class MlnFfiRuntimeOptions(
  val cacheFile: File,
  val maximumCacheSizeBytes: Long? = null,
)

/** Uses one stable lexical identity for a cache database without requiring it to exist yet. */
internal fun MlnFfiRuntimeOptions.normalized(): MlnFfiRuntimeOptions {
  val normalizedFile = cacheFile.absoluteFile.normalize()
  return if (normalizedFile == cacheFile) this else copy(cacheFile = normalizedFile)
}

/** The one process-wide MapLibre Native configuration and the runtime that owns its cache. */
internal object MlnFfiApplication {
  private class State(val options: MlnFfiRuntimeOptions, val offlineManager: MlnFfiOfflineManager)

  @Volatile private var state: State? = null

  fun configure(rawOptions: MlnFfiRuntimeOptions) {
    val options = rawOptions.normalized()
    synchronized(this) {
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

  val options: MlnFfiRuntimeOptions
    get() = requireState().options

  val offlineManager: MlnFfiOfflineManager
    get() = requireState().offlineManager

  private fun requireState(): State =
    checkNotNull(state) {
      "MapLibre is not configured. Call MapLibre.configure(...) before opening a map."
    }

  /**
   * Stops and forgets the process-wide owner. Tests only; production configuration is permanent.
   */
  internal fun resetForTest(): Boolean {
    val previous = synchronized(this) { state.also { state = null } } ?: return true
    return previous.offlineManager.closeForTest()
  }
}

private fun MlnFfiRuntimeOptions.describe(): String =
  "cacheFile='$cacheFile', maximumCacheSizeBytes=$maximumCacheSizeBytes"
