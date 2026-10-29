package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.compose.logging.MapLogLevel
import org.maplibre.compose.logging.MapLogSource
import org.maplibre.compose.logging.MapLogger
import org.maplibre.compose.logging.MapLogging
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.runMapTest
import org.maplibre.nativeffi.LogSeverityFlag
import org.maplibre.nativeffi.loadNativeLibrary
import org.maplibre.nativeffi.setLogAsyncSeverityMask

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
      assertTrue(exited, "JVM hung during native shutdown:\n$log")
      assertTrue(
        log.lineSequence().any { it == ReadyToExit },
        "Child exited with ${process.exitValue()} before finishing cleanup:\n$log",
      )
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
    val received = CompletableDeferred<Unit>()
    MapLogging.logger = MapLogger { record ->
      System.err.println(record)
      if (
        record.source == MapLogSource.NativeEngine &&
          record.category == "ParseStyle" &&
          record.level == MapLogLevel.Warning
      ) {
        received.complete(Unit)
      }
    }
    System.err.println("Loading MapLibre Native")
    loadNativeLibrary()
    setLogAsyncSeverityMask(LogSeverityFlag.WARNING)
    runMapTest {
      val cache = FfiTestPlatform.createCacheFile()
      val runtime = createNativeMapRuntime(MlnFfiRuntimeOptions(cacheFile = cache))
      try {
        // An invalid center emits a native parser warning through Compose's installed log bridge.
        val style = BaseStyle.Json("""{"version":8,"center":false,"sources":{},"layers":[]}""")
        val snapshotter = runtime.createSnapshotter(style)
        try {
          snapshotter.capture(DpSize(64.dp, 64.dp))
          checkNotNull(withTimeoutOrNull(10.seconds) { received.await() }) {
            "No asynchronous native parser warning"
          }
        } finally {
          snapshotter.close()
          snapshotter.awaitClosed()
        }
      } finally {
        runtime.close()
        runtime.awaitClosed()
        FfiTestPlatform.deleteCacheFile(cache)
      }
      if (System.getProperty("os.name").startsWith("Windows")) {
        // Exercise the worker-scoped Direct3D window as well as native runtime shutdown. The
        // fixture closes the map and render bridge; the shared consumer window stays alive.
        BridgeMapFixture.create().use { fixture ->
          fixture.loadStyle(BaseStyle.Empty)
          fixture.awaitRendered()
        }
      }
    }
    println(ReadyToExit)
    exitProcess(0)
  }
}

private const val ReadyToExit = "Native callback and runtime cleanup finished; requesting JVM exit"
