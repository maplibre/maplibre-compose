package org.maplibre.compose.map

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.log.LogCallback
import org.maplibre.nativeffi.log.LogEvent
import org.maplibre.nativeffi.log.LogSeverity
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapOptions
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.RuntimeOptions

/** Native static destruction runs after JVM shutdown, so only a child process tests its exit. */
class NativeProcessExitTest {
  @Test fun exits_with_the_native_log_callback_installed() = assertExits("installed")

  @Test fun exits_after_clearing_the_native_log_callback() = assertExits("cleared")

  private fun assertExits(callbackState: String) {
    repeat(3) {
      val output = Files.createTempFile("maplibre-compose-exit-", ".log")
      val java = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
      val process =
        ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", java).toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            checkNotNull(System.getProperty("org.maplibre.compose.test.classpath")),
            NativeProcessExitProbe::class.java.name,
            callbackState,
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
        assertTrue(exited, "JVM hung during native shutdown ($callbackState):\n$log")
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
}

/** Also accepts `raw` to compare the logger regression with an older binding on the classpath. */
internal object NativeProcessExitProbe {
  @JvmStatic
  fun main(args: Array<String>) {
    val callbackState = args.first()
    require(callbackState == "installed" || callbackState == "cleared")
    Maplibre.loadNativeLibrary()
    if (args.getOrNull(1) != "raw")
      runBlocking {
        repeat(3) {
          val cache = FfiTestPlatform.createCacheFile()
          val runtime = createNativeMapRuntime(MlnFfiRuntimeOptions(cacheFile = cache))
          try {
            val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
            try {
              repeat(2) {
                val snapshot = snapshotter.capture(MapSnapshotRequest(width = 64, height = 64))
                check(snapshot.width == 64 && snapshot.height == 64)
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
        }
      }
    val caller = Thread.currentThread()
    val callbackThread = AtomicReference<Thread>()
    val received = CountDownLatch(1)
    Maplibre.setAsyncLogSeverities(setOf(LogSeverity.WARNING))
    Maplibre.setLogCallback(
      LogCallback { record ->
        if (record.event == LogEvent.PARSE_STYLE && record.severity == LogSeverity.WARNING) {
          callbackThread.set(Thread.currentThread())
          received.countDown()
        }
        true
      }
    )
    RuntimeHandle.create(RuntimeOptions()).use { runtime ->
      MapHandle.create(runtime, MapOptions()).use { map ->
        map.setStyleJson(
          """{"version":8,"center":false,"sources":{},"layers":[]}""".encodeToByteArray()
        )
        check(received.await(10, TimeUnit.SECONDS)) { "No asynchronous native parser warning" }
        check(callbackThread.get() !== caller) { "Native logging was synchronous" }
      }
    }
    if (callbackState == "cleared") Maplibre.clearLogCallback()
    println(READY_TO_EXIT)
    exitProcess(0)
  }
}

private const val READY_TO_EXIT =
  "Native callback and runtime cleanup finished; requesting JVM exit"
