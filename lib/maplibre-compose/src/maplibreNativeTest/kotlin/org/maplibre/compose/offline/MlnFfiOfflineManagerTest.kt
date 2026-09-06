package org.maplibre.compose.offline

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
  fun an_initial_cache_budget_failure_fails_manager_construction() {
    assertFailsWith<IllegalStateException> {
      MlnFfiOfflineManager(options.copy(maximumCacheSizeBytes = -1))
    }
  }
}
