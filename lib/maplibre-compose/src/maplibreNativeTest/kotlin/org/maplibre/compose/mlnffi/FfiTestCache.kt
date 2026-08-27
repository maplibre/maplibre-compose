package org.maplibre.compose.mlnffi

import kotlinx.io.files.Path

/**
 * A cache database that belongs to one test, and the runtime options over it.
 *
 * Every test owns its cache path, so tests that share a process do not share cached tiles.
 */
internal class FfiTestCache(maximumCacheSizeBytes: Long? = null) : AutoCloseable {

  val file: Path = FfiTestPlatform.createCacheFile()

  val options: MlnFfiRuntimeOptions =
    MlnFfiRuntimeOptions(cacheFile = file, maximumCacheSizeBytes = maximumCacheSizeBytes)

  /** Configures the process-wide application over this cache. */
  fun configure() {
    MlnFfiApplication.configure(options)
  }

  /** Deletes the cache file. Resetting the application is the caller's own teardown step. */
  override fun close() {
    FfiTestPlatform.deleteCacheFile(file)
  }
}
