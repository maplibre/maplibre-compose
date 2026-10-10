package org.maplibre.compose.offline

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.nativeffi.generated.AmbientCacheOperation
import org.maplibre.nativeffi.generated.GeneratedApi
import org.maplibre.nativeffi.generated.RuntimeHandle

/**
 * Verifies that two MapLibre runtimes can use one persistent cache database.
 *
 * Offline storage usable without a map can open a second runtime on the same cache file. If that is
 * not safe, an FFI platform needs a process-level runtime service instead. Each runtime invalidates
 * the ambient cache so the test reaches the database.
 */
class MlnFfiSharedCacheDatabaseTest {

  @Test
  fun two_runtimes_can_use_the_same_cache_database() = runBlocking {
    val cache = FfiTestPlatform.createCacheFile()
    var firstRuntime: RuntimeHandle? = null
    var secondRuntime: RuntimeHandle? = null
    try {
      val firstHandle = createRuntime(cache)
      firstRuntime = firstHandle

      val secondResult = runCatching { createRuntime(cache) }
      assertTrue(
        secondResult.isSuccess,
        "A second runtime could not share the cache database, so FFI offline support needs a " +
          "process-level runtime service rather than a runtime of its own: " +
          "${secondResult.exceptionOrNull()}",
      )
      val secondHandle = secondResult.getOrThrow()
      secondRuntime = secondHandle

      val touches =
        listOf(
            async { runCatching { touchCache(firstHandle) } },
            async { runCatching { touchCache(secondHandle) } },
          )
          .awaitAll()
      assertTrue(
        touches.all { it.isSuccess },
        "Both runtimes opened the cache, but an explicit cache operation failed: " +
          touches.mapNotNull { it.exceptionOrNull() },
      )
    } finally {
      secondRuntime?.release()?.await()
      firstRuntime?.release()?.await()
      FfiTestPlatform.deleteCacheFile(cache)
    }
  }

  private fun createRuntime(cacheFile: Path): RuntimeHandle =
    GeneratedApi.runtimeCreate(
      GeneratedApi.runtimeOptionsDefault().copy(cachePath = cacheFile.toString())
    )

  private suspend fun touchCache(runtime: RuntimeHandle) {
    withTimeout(CacheOperationTimeout) {
      runtime.runAmbientCacheOperation(AmbientCacheOperation.INVALIDATE).await()
    }
  }

  private companion object {
    val CacheOperationTimeout = 30.seconds
  }
}
