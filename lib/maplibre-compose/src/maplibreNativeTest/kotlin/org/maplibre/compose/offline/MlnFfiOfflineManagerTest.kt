package org.maplibre.compose.offline

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions

/** Exercises the application cache's offline manager without a UI. */
class MlnFfiOfflineManagerTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val options = MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun an_initial_cache_budget_failure_is_published_and_rejects_operations() = runBlocking {
    val manager = MlnFfiOfflineManager(options.copy(maximumCacheSizeBytes = -1))
    try {
      withTimeout(5_000L) {
        assertFailsWith<OfflineManagerException> { manager.clearAmbientCache() }
      }
      assertIs<OfflineManagerState.Failed>(manager.state.value)
    } finally {
      manager.close()
      withTimeout(5_000L) { manager.awaitClosed() }
    }
    Unit
  }
}
