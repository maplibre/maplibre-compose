package org.maplibre.compose.map

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.logging.MapLogLevel
import org.maplibre.compose.logging.MapLogSource
import org.maplibre.compose.logging.MapLogger
import org.maplibre.compose.logging.MapLogging
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.log.LogSeverity

/** Native static destruction runs after JVM shutdown, so only a child process tests its exit. */
class NativeProcessExitTest {
  @Test
  fun exits_after_native_logging_and_compose_runtime_cleanup() {
    val output = Files.createTempFile("maplibre-compose-exit-", ".log")
    val java = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
    val process =
      ProcessBuilder(
          Path.of(System.getProperty("java.home"), "bin", java).toString(),
          "--enable-native-access=ALL-UNNAMED",
          "-cp",
          checkNotNull(System.getProperty("org.maplibre.compose.test.classpath")),
          NativeProcessExitProbe::class.java.name,
        )
        .redirectErrorStream(true)
        .redirectOutput(output.toFile())
        .start()
    try {
      val exited = process.waitFor(30, TimeUnit.SECONDS)
      val log = Files.readString(output)
      assertTrue(
        log.lineSequence().any { it == READY_TO_EXIT },
        "Child did not finish cleanup:\n$log",
      )
      assertTrue(exited, "JVM hung during native shutdown:\n$log")
      assertEquals(0, process.exitValue(), log)
    } finally {
      if (process.isAlive) {
        process.destroyForcibly()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "Could not stop child ${process.pid()}" }
      }
      Files.deleteIfExists(output)
    }
  }
}

internal object NativeProcessExitProbe {
  @JvmStatic
  fun main(args: Array<String>) {
    val received = CountDownLatch(1)
    MapLogging.logger = MapLogger { record ->
      if (
        record.source == MapLogSource.NativeEngine &&
          record.category == "ParseStyle" &&
          record.level == MapLogLevel.Warning
      ) {
        received.countDown()
      }
    }
    Maplibre.loadNativeLibrary()
    Maplibre.setAsyncLogSeverities(setOf(LogSeverity.WARNING))
    runBlocking {
      val cache = FfiTestPlatform.createCacheFile()
      val runtime = createNativeMapRuntime(MlnFfiRuntimeOptions(cacheFile = cache))
      try {
        // An invalid center emits a native parser warning through Compose's installed log bridge.
        val style = BaseStyle.Json("""{"version":8,"center":false,"sources":{},"layers":[]}""")
        val snapshotter = runtime.createSnapshotter(style)
        try {
          snapshotter.capture(MapSnapshotRequest(width = 64, height = 64))
          check(received.await(10, TimeUnit.SECONDS)) { "No asynchronous native parser warning" }
        } finally {
          snapshotter.close()
          snapshotter.awaitClosed()
        }
      } finally {
        runtime.close()
        runtime.awaitClosed()
        FfiTestPlatform.deleteCacheFile(cache)
      }
    }
    println(READY_TO_EXIT)
    exitProcess(0)
  }
}

private const val READY_TO_EXIT =
  "Native callback and runtime cleanup finished; requesting JVM exit"
