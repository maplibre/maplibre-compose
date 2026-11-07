package org.maplibre.compose.resource

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.TestMapMain
import org.maplibre.compose.testing.runMapTest

class MlnFfiRuntimeCacheFallbackTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun a_cache_file_that_cannot_be_created_leaves_the_runtime_usable() = runMapTest {
    // A regular file where the cache's directory should be, so MapLibre can't create the database.
    val blocker = Path(requireNotNull(cacheFile.parent), "blocker")
    SystemFileSystem.sink(blocker).buffered().use { it.writeString("not a directory") }
    val runtime =
      MlnFfiRuntime(
        MlnFfiRuntimeOptions(
          cacheFile = Path(blocker, "cache.db"),
          mainDispatcher = TestMapMain.dispatcher,
        ),
      )
    runtime.start()
    try {
      runtime.awaitRuntime()
    } finally {
      runtime.close()
      runtime.awaitClosed()
    }
  }
}
