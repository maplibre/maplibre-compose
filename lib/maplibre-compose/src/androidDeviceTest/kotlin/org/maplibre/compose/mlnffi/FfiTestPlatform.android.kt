package org.maplibre.compose.mlnffi

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.io.files.Path
import org.junit.Assume.assumeTrue

internal actual object FfiTestPlatform {
  actual val runtimeCapabilities = FfiTestRuntimeCapabilities(customGeometrySourceCallbacks = true)

  actual fun initialize() {
    AndroidMlnFfiPlatform.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
  }

  actual fun createCacheFile(): Path {
    initialize()
    val directory =
      AndroidMlnFfiPlatform.applicationContext.cacheDir.resolve("ffi-test-${System.nanoTime()}")
    check(directory.mkdirs()) { "Could not create FFI test directory $directory" }
    return Path(directory.resolve("cache.db").absolutePath)
  }

  actual fun deleteCacheFile(file: Path) {
    File(file.toString()).parentFile?.deleteRecursively()
  }

  actual fun createRenderDriver(): FfiTestRenderDriver {
    initialize()
    return AndroidEglTestRenderDriver.create()
  }

  actual fun skip(reason: String): Nothing {
    assumeTrue(reason, false)
    error("JUnit did not abort skipped test: $reason")
  }
}
