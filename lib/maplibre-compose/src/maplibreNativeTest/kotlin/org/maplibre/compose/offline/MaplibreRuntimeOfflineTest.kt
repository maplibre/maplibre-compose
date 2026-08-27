package org.maplibre.compose.offline

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertSame
import org.maplibre.compose.mlnffi.FfiTestCache
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.runtime.MaplibreRuntime

/** Exercises the public runtime acquisition path over the process-wide application. */
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
  fun offline_returns_the_process_offline_manager() {
    assertSame(MlnFfiApplication.offlineManager, MaplibreRuntime.offline)
  }
}
