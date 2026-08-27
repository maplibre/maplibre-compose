package org.maplibre.compose.offline

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.mlnffi.FfiTestCache
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.runtime.MaplibreRuntime

/** Exercises the public runtime's offline members over the process-wide application. */
class MaplibreRuntimeOfflineTest {

  private val cache = FfiTestCache()

  @BeforeTest
  fun configure() {
    MlnFfiApplication.resetForTest()
    // Configured explicitly so the test owns its cache path on every platform.
    cache.configure()
  }

  @AfterTest
  fun cleanUp() {
    MlnFfiApplication.resetForTest()
    cache.close()
  }

  @Test
  fun offline_packs_read_the_process_offline_manager() {
    assertSame(MlnFfiApplication.offlineManager.packs, MaplibreRuntime.offlinePacks)
  }

  @Test
  fun suspending_members_complete_against_the_process_offline_manager() = runBlocking {
    withTimeout(30_000) {
      MaplibreRuntime.setMaximumAmbientCacheSize(16L * 1024 * 1024)
      MaplibreRuntime.invalidateAmbientCache()
    }
  }
}
