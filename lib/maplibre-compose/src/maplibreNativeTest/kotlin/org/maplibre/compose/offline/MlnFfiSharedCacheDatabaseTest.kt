package org.maplibre.compose.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.TestThread
import org.maplibre.nativeffi.error.MaplibreStatus
import org.maplibre.nativeffi.runtime.AmbientCacheOperation
import org.maplibre.nativeffi.runtime.OfflineOperationHandle
import org.maplibre.nativeffi.runtime.RuntimeEventPayload
import org.maplibre.nativeffi.runtime.RuntimeEventType
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.RuntimeOptions

/**
 * Verifies that two MapLibre runtimes can use one persistent cache database.
 *
 * MapLibre binds a runtime to its creating thread and allows only one per thread, so an offline
 * manager usable without a map needs a second runtime opening the same cache file. If that is not
 * safe, an FFI platform needs a process-level runtime service instead. Each runtime invalidates the
 * ambient cache so the test reaches the database instead of only pumping an idle runtime.
 */
class MlnFfiSharedCacheDatabaseTest {

  @Test
  fun two_runtimes_can_use_the_same_cache_database() {
    val cache = FfiTestPlatform.createCacheFile()

    val first = TestThread("ffi-cache-first")
    val second = TestThread("ffi-cache-second")
    var firstRuntime: RuntimeHandle? = null
    var secondRuntime: RuntimeHandle? = null
    try {
      val firstHandle = first.submit { createRuntime(cache) }
      firstRuntime = firstHandle

      val secondResult = second.submit { runCatching { createRuntime(cache) } }
      assertTrue(
        secondResult.isSuccess,
        "A second runtime could not share the cache database, so FFI offline support needs a " +
          "process-level runtime service rather than a runtime of its own: " +
          "${secondResult.exceptionOrNull()}",
      )
      val secondHandle = secondResult.getOrThrow()
      secondRuntime = secondHandle

      val touches = runBlocking {
        listOf(
            async(first.dispatcher) { runCatching { touchCache(firstHandle) } },
            async(second.dispatcher) { runCatching { touchCache(secondHandle) } },
          )
          .awaitAll()
      }
      assertTrue(
        touches.all { it.isSuccess },
        "Both runtimes opened the cache, but an explicit cache operation failed: " +
          touches.mapNotNull { it.exceptionOrNull() },
      )
    } finally {
      secondRuntime?.let { runtime -> second.submit { runtime.close() } }
      firstRuntime?.let { runtime -> first.submit { runtime.close() } }
      first.close()
      second.close()
      FfiTestPlatform.deleteCacheFile(cache)
    }
  }

  private fun createRuntime(cacheFile: Path): RuntimeHandle =
    RuntimeHandle.create(RuntimeOptions().also { it.cachePath = cacheFile.toString() })

  private fun touchCache(runtime: RuntimeHandle) {
    val operation = runtime.startAmbientCacheOperation(AmbientCacheOperation.INVALIDATE)
    try {
      val started = TimeSource.Monotonic.markNow()
      while (started.elapsedNow() < CACHE_OPERATION_TIMEOUT) {
        runtime.pump(100)
        val completion = runtime.completionFor(operation) ?: continue
        assertEquals(RuntimeEventType.OFFLINE_OPERATION_COMPLETED, completion.first)
        assertEquals(operation.kind, completion.second.operationKind)
        assertEquals(MaplibreStatus.OK.nativeCode, completion.second.resultStatus)
        return
      }
      error("Cache operation ${operation.id} did not complete")
    } finally {
      operation.close()
    }
  }

  private fun RuntimeHandle.completionFor(
    operation: OfflineOperationHandle<*>
  ): Pair<RuntimeEventType, RuntimeEventPayload.OfflineOperationCompleted>? =
    drainEvents().events.firstNotNullOfOrNull { event ->
      val completion = event.payload as? RuntimeEventPayload.OfflineOperationCompleted
      if (completion?.operationId == operation.id) event.type to completion else null
    }

  private companion object {
    val CACHE_OPERATION_TIMEOUT = 30.seconds
  }
}
