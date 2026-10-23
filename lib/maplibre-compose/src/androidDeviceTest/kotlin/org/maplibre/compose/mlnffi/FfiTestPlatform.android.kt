package org.maplibre.compose.mlnffi

import java.io.File
import kotlinx.io.files.Path
import org.junit.Assume.assumeTrue

internal actual object FfiTestPlatform {
  actual fun initialize() {
    AndroidMlnFfiPlatform.initialize()
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
