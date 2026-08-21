package org.maplibre.compose.offline

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.TestThread
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.RuntimeOptions

/**
 * Answers whether two MapLibre runtimes can share one persistent cache database.
 *
 * MapLibre binds a runtime to its creating thread and allows only one per thread, so an offline
 * manager usable without a map needs a second runtime opening the same cache file. If that is not
 * safe, an FFI platform needs a process-level runtime service instead.
 */
class MlnFfiSharedCacheDatabaseTest {

  @Test
  fun two_runtimes_can_open_the_same_cache_database() {
    val cache = FfiTestPlatform.createCacheFile()

    val first = TestThread("ffi-cache-first")
    val second = TestThread("ffi-cache-second")
    try {
      val firstRuntime = first.submit { createRuntime(cache) }
      assertNotNull(firstRuntime, "the first runtime should be created")

      val secondResult = second.submit { runCatching { createRuntime(cache) } }

      // Pump both briefly so each actually touches the database rather than only opening it.
      first.submit { repeat(20) { firstRuntime.pump(0) } }
      secondResult.getOrNull()?.let { runtime ->
        second.submit { repeat(20) { runtime.pump(0) } }
        second.submit { runtime.close() }
      }
      first.submit { firstRuntime.close() }

      assertTrue(
        secondResult.isSuccess,
        "A second runtime could not share the cache database, so FFI offline support needs a " +
          "process-level runtime service rather than a runtime of its own: " +
          "${secondResult.exceptionOrNull()}",
      )
    } finally {
      first.close()
      second.close()
      FfiTestPlatform.deleteCacheFile(cache)
    }
  }

  private fun createRuntime(cacheFile: Path): RuntimeHandle =
    RuntimeHandle.create(RuntimeOptions().also { it.cachePath = cacheFile.toString() })
}
