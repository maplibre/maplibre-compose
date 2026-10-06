package org.maplibre.compose.offline

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions

/** Exercises the application cache's offline storage without a UI. */
class MlnFfiOfflineStorageTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val options = MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun an_initial_cache_budget_failure_is_published_and_rejects_operations() = runBlocking {
    val configured = options.copy(maximumCacheSizeBytes = -1)
    val owner = MlnFfiRuntime(configured)
    val storage = MlnFfiOfflineStorage(owner)
    owner.start()
    try {
      withTimeout(5_000L) {
        assertFailsWith<OfflineStorageException> { storage.clearAmbientCache() }
      }
      assertIs<OfflineStorageState.Failed>(storage.state.value)
    } finally {
      storage.close()
      owner.close()
      withTimeout(5_000L) { owner.awaitClosed() }
    }
    Unit
  }
}
