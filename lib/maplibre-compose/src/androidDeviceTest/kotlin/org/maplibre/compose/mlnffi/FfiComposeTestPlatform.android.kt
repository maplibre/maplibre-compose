package org.maplibre.compose.mlnffi

import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import java.util.concurrent.atomic.AtomicLong
import org.maplibre.compose.map.ProcessNativeMapRuntime

@OptIn(ExperimentalTestApi::class)
internal actual fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  FfiTestPlatform.initialize()
  val watchdog = startHangWatchdog()
  try {
    runAndroidComposeUiTest<ComponentActivity> {
      try {
        block(this)
      } finally {
        disposeFfiTestContent()
      }
    }
  } finally {
    watchdog.interrupt()
    // Bounded, so a stuck stderr dump cannot hold teardown for the watchdog's sake.
    watchdog.join(1_000)
    ProcessNativeMapRuntime.resetForTest()
    MlnFfiApplication.resetForTest()
  }
}

@OptIn(ExperimentalTestApi::class)
internal actual fun runPlainComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  runAndroidComposeUiTest<ComponentActivity> { block(this) }
}

/**
 * How long a wait may stay silent before the watchdog dumps stacks and kills this process.
 *
 * Compose's `waitUntil` checks its timeout between frame pumps. A blocked native render never
 * reaches that check, so the 30 s timeout never fires and the Android job sits until GitHub cancels
 * it. [pingFfiTestHangWatchdog] moves the deadline, so a long test with many waits is not killed
 * from process start. Orchestrator isolates the kill to one method.
 */
private const val HANG_DUMP_DELAY_MILLIS = 50_000L

private val hangDeadlineElapsedRealtime = AtomicLong(0L)

internal actual fun pingFfiTestHangWatchdog(timeoutMillis: Long) {
  hangDeadlineElapsedRealtime.set(SystemClock.elapsedRealtime() + timeoutMillis)
}

/** Fails a blocked FFI Compose test so the instrumentation process cannot stay silent. */
private fun startHangWatchdog(): Thread {
  pingFfiTestHangWatchdog(HANG_DUMP_DELAY_MILLIS)
  val watchdog = Thread {
    while (true) {
      val remaining = hangDeadlineElapsedRealtime.get() - SystemClock.elapsedRealtime()
      if (remaining <= 0L) break
      try {
        Thread.sleep(remaining)
      } catch (_: InterruptedException) {
        return@Thread
      }
    }
    System.err.println(
      "An FFI Compose test has been silent for $HANG_DUMP_DELAY_MILLIS ms; dumping all threads:"
    )
    for ((thread, stack) in Thread.getAllStackTraces()) {
      System.err.println(thread)
      for (frame in stack) System.err.println("\tat $frame")
    }
    System.err.println(
      "Killing this instrumentation process so a blocked frame pump cannot hold the job."
    )
    System.err.flush()
    Process.killProcess(Process.myPid())
  }
  watchdog.name = "ffi-test-hang-watchdog"
  watchdog.isDaemon = true
  watchdog.start()
  return watchdog
}

@OptIn(ExperimentalTestApi::class)
internal actual fun ComposeUiTest.setFfiTestMapContent(
  runtimeOptions: MlnFfiRuntimeOptions,
  presentationCount: Int,
  content: @Composable () -> Unit,
) {
  require(presentationCount > 0) { "A map test must prepare at least one presentation" }
  MlnFfiApplication.configure(runtimeOptions)
  setContent(content)
}
