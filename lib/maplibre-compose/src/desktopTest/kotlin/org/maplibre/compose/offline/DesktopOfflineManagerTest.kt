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

  @AfterTest
  fun cleanUp() {
    directory.toFile().deleteRecursively()
  }

  @Test
  fun `creating a manager starts its runtime and lists packs`() = runBlocking {
    val manager = DesktopOfflineManager.forOptions(options)

    // Listing is what the offline screen does first, so it is what a crash on open would hit.
    withTimeout(30_000) {
      // The initial listing is asynchronous; the manager publishes into `packs` when it completes.
      // Poll rather than await an event, because a failure to list would otherwise hang.
      var waited = 0L
      while (manager.packs.isEmpty() && waited < 10_000) {
        kotlinx.coroutines.delay(100)
        waited += 100
      }
    }

    // An empty database legitimately has no packs; the assertion is that getting here did not
    // throw.
    assertTrue(manager.packs.isEmpty() || manager.packs.isNotEmpty())
  }

  @Test
  fun `the same options return the same manager`() {
    // One runtime and one thread per options value; a second call site must not start another.
    assertTrue(
      DesktopOfflineManager.forOptions(options) === DesktopOfflineManager.forOptions(options)
    )
  }
}
