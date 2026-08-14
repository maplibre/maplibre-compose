package org.maplibre.compose.mlnffi

import java.io.File
import java.nio.file.Files
import kotlinx.io.files.Path
import org.maplibre.nativeffi.Maplibre

internal actual object FfiTestPlatform {
  actual fun initialize() {
    Maplibre.loadNativeLibrary()
  }

  actual fun createCacheFile(): Path {
    initialize()
    return Path(Files.createTempDirectory("maplibre-ffi-test").resolve("cache.db").toString())
  }

  actual fun deleteCacheFile(file: Path) {
    file.parent?.let { File(it.toString()).deleteRecursively() }
  }

  actual fun createRenderDriver(): FfiTestRenderDriver {
    initialize()
    return ProductionBridgeTestRenderDriver.create()
  }
}
