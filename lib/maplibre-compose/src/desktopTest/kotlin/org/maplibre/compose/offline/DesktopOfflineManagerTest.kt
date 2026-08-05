package org.maplibre.compose.offline

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.desktop.DesktopRuntimeOptions

/** Exercises the offline manager without a UI. */
class DesktopOfflineManagerTest {

  private val directory = Files.createTempDirectory("maplibre-offline-test")

  private val options =
    DesktopRuntimeOptions(cachePath = directory.resolve("cache.db"), maximumCacheSizeBytes = null)

  private val budgetedOptions =
    DesktopRuntimeOptions(
      cachePath = directory.resolve("budgeted-cache.db"),
      maximumCacheSizeBytes = 8L * 1024 * 1024,
    )

  @AfterTest
  fun cleanUp() {
    // Managers otherwise live for the life of the process, leaving a thread and an open database
    // behind for every later test in the run.
    DesktopOfflineManager.disposeForTest(options)
    DesktopOfflineManager.disposeForTest(budgetedOptions)
    directory.toFile().deleteRecursively()
  }

  /**
   * mbgl exposes no getter for the budget, so this asserts only that the asynchronous call runs to
   * completion — one that was never started, or whose completion event was lost, hangs here.
   */
  @Test
  fun `changing the maximum ambient cache size completes, twice, with different budgets`() =
    runBlocking {
      val manager = DesktopOfflineManager.forOptions(options)

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
  fun `changing the cache size completes on a runtime that was created with a budget`() =
    runBlocking {
      val manager = DesktopOfflineManager.forOptions(budgetedOptions)

      withTimeout(30_000) {
        manager.setMaximumAmbientCacheSize(4L * 1024 * 1024)
        // A completion consumed by the wrong owner shows up as the *next* operation never
        // finishing, so a second unrelated operation is what detects it.
        manager.invalidateAmbientCache()
      }
    }

  @Test
  fun `the same options return the same manager`() {
    // One runtime and one thread per options value; a second call site must not start another.
    assertTrue(
      DesktopOfflineManager.forOptions(options) === DesktopOfflineManager.forOptions(options)
    )
  }
}
