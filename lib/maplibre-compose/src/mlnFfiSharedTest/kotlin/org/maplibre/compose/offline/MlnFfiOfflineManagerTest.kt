package org.maplibre.compose.offline

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.resource.MlnFfiCacheDatabaseRegistry
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/** Exercises the offline manager without a UI. */
class MlnFfiOfflineManagerTest {

  private val cachePath = FfiTestPlatform.createCachePath()
  private val directory = requireNotNull(cachePath.parent).toFile()

  private val options = MlnFfiRuntimeOptions(cachePath = cachePath, maximumCacheSizeBytes = null)

  private val budgetedOptions =
    MlnFfiRuntimeOptions(
      cachePath = directory.resolve("budgeted-cache.db").toPath(),
      maximumCacheSizeBytes = 8L * 1024 * 1024,
    )

  @AfterTest
  fun cleanUp() {
    // Managers otherwise live for the life of the process, leaving a thread and an open database
    // behind for every later test in the run.
    MlnFfiOfflineManager.disposeForTest(options)
    MlnFfiOfflineManager.disposeForTest(budgetedOptions)
    FfiTestPlatform.deleteCachePath(cachePath)
  }

  /**
   * mbgl exposes no getter for the budget, so this asserts only that the asynchronous call runs to
   * completion — one that was never started, or whose completion event was lost, hangs here.
   */
  @Test
  fun changing_the_maximum_ambient_cache_size_completes_twice_with_different_budgets() =
    runBlocking {
      val manager = MlnFfiOfflineManager.forOptions(options)

      withTimeout(30_000) {
        manager.setMaximumAmbientCacheSize(16L * 1024 * 1024)
        // Zero is what a caller turning ambient caching off passes.
        manager.setMaximumAmbientCacheSize(0)
      }
    }

  /**
   * A runtime created with a budget already made this native call once, so this is the arrangement
   * in which a caller's completion event could be retired by the earlier request's id.
   */
  @Test
  fun changing_the_cache_size_completes_on_a_runtime_that_was_created_with_a_budget() =
    runBlocking {
      val manager = MlnFfiOfflineManager.forOptions(budgetedOptions)

      withTimeout(30_000) {
        manager.setMaximumAmbientCacheSize(4L * 1024 * 1024)
        // A completion consumed by the wrong owner shows up as the *next* operation never
        // finishing, so a second unrelated operation is what detects it.
        manager.invalidateAmbientCache()
      }
    }

  @Test
  fun cancelling_a_size_change_waiting_for_the_database_permit_does_not_block_the_next_change() =
    runBlocking {
      val manager = MlnFfiOfflineManager.forOptions(options)
      // Proves startup has established the registry lease before the test takes its permit.
      withTimeout(30_000) { manager.invalidateAmbientCache() }
      val held = MlnFfiCacheDatabaseRegistry.acquireWritePermit(cachePath)
      try {
        val cancelled = launch { manager.setMaximumAmbientCacheSize(16L * 1024 * 1024) }
        withTimeout(30_000) {
          while (MlnFfiCacheDatabaseRegistry.queuedWriteCount(cachePath) == 0) yield()
        }
        cancelled.cancelAndJoin()
      } finally {
        held.close()
      }

      withTimeout(30_000) { manager.setMaximumAmbientCacheSize(8L * 1024 * 1024) }
    }

  @Test
  fun the_same_options_return_the_same_manager() {
    // One runtime and one thread per options value; a second call site must not start another.
    assertTrue(
      MlnFfiOfflineManager.forOptions(options) === MlnFfiOfflineManager.forOptions(options)
    )
  }

  @Test
  fun normalized_cache_paths_return_the_same_manager() {
    val alias = cachePath.parent.resolve("subdirectory").resolve("..").resolve(cachePath.fileName)
    val aliasedOptions = options.copy(cachePath = alias)

    assertSame(
      MlnFfiOfflineManager.forOptions(options),
      MlnFfiOfflineManager.forOptions(aliasedOptions),
    )
  }

  @Test
  fun the_same_cache_path_rejects_a_different_budget() {
    MlnFfiOfflineManager.forOptions(options)

    assertFailsWith<IllegalStateException> {
      MlnFfiOfflineManager.forOptions(options.copy(maximumCacheSizeBytes = 1024))
    }
  }

  @Test
  fun a_manager_rejects_a_pack_owned_by_another_manager() {
    val manager = MlnFfiOfflineManager.forOptions(options)
    val otherManager = MlnFfiOfflineManager.forOptions(budgetedOptions)
    val foreignPack = OfflinePack(otherManager, 1, DEFINITION, initialMetadata = null)

    assertFailsWith<OfflineManagerException> { manager.resume(foreignPack) }
  }

  private companion object {
    val DEFINITION =
      OfflinePackDefinition.TilePyramid(
        styleUrl = "https://example.invalid/style.json",
        bounds = BoundingBox(southwest = Position(-1.0, -1.0), northeast = Position(1.0, 1.0)),
      )
  }
}
