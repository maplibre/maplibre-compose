package org.maplibre.compose.mlnffi

import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import org.maplibre.nativeffi.Maplibre

internal actual object FfiTestPlatform {
  actual val runtimeCapabilities = FfiTestRuntimeCapabilities(customGeometrySourceCallbacks = true)

  actual fun initialize() {
    Maplibre.loadNativeLibrary()
  }

  actual fun createCacheFile(): File {
    initialize()
    return Files.createTempDirectory("maplibre-ffi-test").resolve("cache.db").toFile()
  }

  actual fun deleteCacheFile(file: File) {
    file.parentFile?.deleteRecursively()
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
