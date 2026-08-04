package org.maplibre.compose.offline

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.desktop.DesktopRuntimeOptions

/**
 * Exercises the offline manager without a UI.
 *
 * The manager owns a runtime, a thread, and a database, and none of that needs a window — so a
 * crash on opening the offline screen should be reproducible here rather than only by clicking.
 */
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
    // Managers live for the life of the process in production, so a test that does not dispose its
    // own leaves a thread and an open database behind for every later test in the run.
    DesktopOfflineManager.disposeForTest(options)
    DesktopOfflineManager.disposeForTest(budgetedOptions)
    directory.toFile().deleteRecursively()
  }

  /**
   * Nothing can read the budget back — mbgl keeps it on the database file source and exposes no
   * getter — so what is asserted is that the operation runs to completion, not that an eviction
   * happened. That is still the interesting half: the call is asynchronous, and an implementation
   * that never started it, or that lost its completion event, would hang here instead of returning.
   */
  @Test
  fun `changing the maximum ambient cache size completes, twice, with different budgets`() =
    runBlocking {
      val manager = DesktopOfflineManager.forOptions(options)

      withTimeout(30_000) {
        manager.setMaximumAmbientCacheSize(16L * 1024 * 1024)
        // Lowering is the direction that evicts, and zero is what a caller turning ambient caching
        // off passes, so it is the value most likely to be handled specially somewhere.
        manager.setMaximumAmbientCacheSize(0)
      }
    }

  /**
   * A runtime created with a budget already applies one through the same native call, from
   * [AmbientCacheSizeRequest], and that request retires the first completion event whose id matches
   * its own. This is therefore the arrangement in which a caller's own operation could go missing
   * on the way back.
   */
  @Test
  fun `changing the cache size completes on a runtime that was created with a budget`() =
    runBlocking {
      val manager = DesktopOfflineManager.forOptions(budgetedOptions)

      withTimeout(30_000) {
        manager.setMaximumAmbientCacheSize(4L * 1024 * 1024)
        // A second, unrelated operation after it, because a completion consumed by the wrong owner
        // shows up as the *next* operation never finishing rather than as this one failing.
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
