package org.maplibre.compose.offline

import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.RuntimeOptions

/**
 * Answers whether two MapLibre runtimes can share one persistent cache database.
 *
 * This decides the shape of desktop offline support. MapLibre binds a runtime to its creating
 * thread and allows only one per thread, so a map owns a runtime on its own thread. An offline
 * manager that is usable without a map therefore needs a second runtime on a second thread — and
 * both would open the same cache file.
 *
 * If that is safe, the offline manager can own a runtime of its own. If it is not, desktop needs a
 * process-level runtime service with every map serialized onto one owner thread, which is a much
 * larger change. The plan calls for measuring this rather than assuming it, so this test is the
 * measurement, and it records the answer where the offline code will be written.
 */
class SharedCacheDatabaseTest {

  @Test
  fun `two runtimes can open the same cache database`() {
    val directory = Files.createTempDirectory("maplibre-shared-cache")
    val cache = directory.resolve("cache.db")

    val first = Executors.newSingleThreadExecutor()
    val second = Executors.newSingleThreadExecutor()
    try {
      val firstRuntime = first.submit<RuntimeHandle> { createRuntime(cache.toString()) }.get()
      assertNotNull(firstRuntime, "the first runtime should be created")

      // The question: does a second runtime on a second thread reject the already-open database?
      val secondResult =
        second
          .submit<Result<RuntimeHandle>> { runCatching { createRuntime(cache.toString()) } }
          .get()

      // Pump both briefly so each actually touches the database rather than only opening it.
      first.submit { repeat(20) { firstRuntime.pump(0) } }.get()
      secondResult.getOrNull()?.let { runtime ->
        second.submit { repeat(20) { runtime.pump(0) } }.get()
        second.submit { runtime.close() }.get()
      }
      first.submit { firstRuntime.close() }.get()

      assertTrue(
        secondResult.isSuccess,
        "A second runtime could not share the cache database, so desktop offline support needs a " +
          "process-level runtime service rather than a runtime of its own: " +
          "${secondResult.exceptionOrNull()}",
      )
    } finally {
      first.shutdown()
      second.shutdown()
      first.awaitTermination(10, TimeUnit.SECONDS)
      second.awaitTermination(10, TimeUnit.SECONDS)
      directory.toFile().deleteRecursively()
    }
  }

  private fun createRuntime(cachePath: String): RuntimeHandle =
    RuntimeHandle.create(RuntimeOptions().also { it.cachePath = cachePath })
}
