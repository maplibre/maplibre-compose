package org.maplibre.compose.offline

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.runtime.MaplibreRuntime

/** Exercises the public runtime acquisition path over the process-wide application. */
class MaplibreRuntimeOfflineTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  @BeforeTest
  fun configure() {
    MlnFfiApplication.resetForTest()
    // Configured explicitly so the test owns its cache path on every platform.
    MlnFfiApplication.configure(MlnFfiRuntimeOptions(cacheFile))
  }

  @AfterTest
  fun cleanUp() {
    MlnFfiApplication.resetForTest()
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun offline_returns_the_process_offline_manager() {
    assertSame(MlnFfiApplication.offlineManager, MaplibreRuntime.offline)
    assertSame(MaplibreRuntime.offline, MaplibreRuntime.offline)
  }

  /** A lost native completion would leave this suspending call hung. */
  @Test
  fun the_runtime_offline_manager_completes_offline_work() = runBlocking {
    val offline = MaplibreRuntime.offline

    withTimeout(30_000) { offline.setMaximumAmbientCacheSize(16L * 1024 * 1024) }
  }
}
