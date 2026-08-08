package org.maplibre.compose.offline

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions

/** Exercises the application cache's offline manager without a UI. */
class MlnFfiOfflineManagerTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()
  private val directory = requireNotNull(cacheFile.parentFile)

  private val options = MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)

  private val budgetedOptions =
    MlnFfiRuntimeOptions(
      cacheFile = directory.resolve("budgeted-cache.db"),
      maximumCacheSizeBytes = 8L * 1024 * 1024,
    )

  private val managers = mutableListOf<MlnFfiOfflineManager>()

  @AfterTest
  fun cleanUp() {
    managers.forEach { it.closeForTest() }
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  /** A lost native completion would leave this suspending call hung. */
  @Test
  fun changing_the_maximum_ambient_cache_size_completes_twice_with_different_budgets() =
    runBlocking {
      val manager = manager()

      withTimeout(30_000) {
        manager.setMaximumAmbientCacheSize(16L * 1024 * 1024)
        manager.setMaximumAmbientCacheSize(0)
      }
    }

  @Test
  fun concurrent_cache_size_changes_are_serialized_by_the_application_owner() = runBlocking {
    val manager = manager(budgetedOptions)

    withTimeout(30_000) {
      listOf(4L, 8L, 16L)
        .map { megabytes -> async { manager.setMaximumAmbientCacheSize(megabytes * 1024 * 1024) } }
        .awaitAll()
      manager.invalidateAmbientCache()
    }
  }

  @Test
  fun an_initial_cache_budget_failure_fails_manager_construction() {
    assertFailsWith<IllegalStateException> {
      MlnFfiOfflineManager(options.copy(maximumCacheSizeBytes = -1))
    }
  }

  private fun manager(options: MlnFfiRuntimeOptions = this.options): MlnFfiOfflineManager =
    MlnFfiOfflineManager(options).also { managers += it }
}
