package org.maplibre.compose.mlnffi

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import org.maplibre.nativeffi.Maplibre

internal actual object FfiTestPlatform {
  actual val runtimeCapabilities = FfiTestRuntimeCapabilities(customGeometrySourceCallbacks = true)

  actual fun initialize() {
    Maplibre.loadNativeLibrary()
  }

  actual fun createCachePath(): Path {
    initialize()
    return Files.createTempDirectory("maplibre-ffi-test").resolve("cache.db")
  }

  actual fun deleteCachePath(path: Path) {
    path.parent.toFile().deleteRecursively()
  }

  actual fun createRenderDriver(): FfiTestRenderDriver {
    initialize()
    return ProductionBridgeTestRenderDriver.create()
  }

  actual fun skip(reason: String): Nothing {
    assumeTrue(reason, false)
    error("JUnit did not abort skipped test: $reason")
  }
}
