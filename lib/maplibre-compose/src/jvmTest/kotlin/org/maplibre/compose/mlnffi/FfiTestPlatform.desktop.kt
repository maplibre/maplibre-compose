package org.maplibre.compose.mlnffi

import java.io.File
import java.nio.file.Files
import kotlinx.io.files.Path
import org.junit.Assume.assumeTrue
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

  actual fun skip(reason: String): Nothing {
    assumeTrue(reason, false)
    error("JUnit did not abort skipped test: $reason")
  }

  actual fun watchForHang(timeoutMillis: Long): AutoCloseable {
    val watchdog =
      Thread(
          {
            try {
              Thread.sleep(timeoutMillis)
            } catch (_: InterruptedException) {
              return@Thread
            }
            System.err.println("The test is still running after $timeoutMillis ms. Thread stacks:")
            Thread.getAllStackTraces().forEach { (thread, stack) ->
              System.err.println("\"${thread.name}\" ${thread.state}")
              stack.forEach { System.err.println("    at $it") }
            }
          },
          "maplibre-test-hang-watchdog",
        )
        .apply {
          isDaemon = true
          start()
        }
    return AutoCloseable { watchdog.interrupt() }
  }
}
