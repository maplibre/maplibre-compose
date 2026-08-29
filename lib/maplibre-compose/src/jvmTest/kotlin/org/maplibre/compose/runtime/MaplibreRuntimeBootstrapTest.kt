package org.maplibre.compose.runtime

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions

/**
 * The public runtime is a first-access path: configuration must load JNI before the shared runtime
 * starts, without a map having been composed.
 */
class MaplibreRuntimeBootstrapTest {

  @AfterTest
  fun cleanUp() {
    MlnFfiApplication.resetForTest()
  }

  @Test
  fun configuration_starts_the_shared_runtime_without_a_composed_map() {
    MlnFfiApplication.resetForTest()
    val directory = Files.createTempDirectory("maplibre-runtime-bootstrap")
    val cache = Path(directory.resolve("cache.db").toString())
    try {
      MlnFfiApplication.configure(MlnFfiRuntimeOptions(cacheFile = cache))
      assertTrue(MlnFfiApplication.isConfigured)
      assertNotNull(MaplibreRuntime.offlinePacks)
    } finally {
      directory.toFile().deleteRecursively()
    }
  }
}
