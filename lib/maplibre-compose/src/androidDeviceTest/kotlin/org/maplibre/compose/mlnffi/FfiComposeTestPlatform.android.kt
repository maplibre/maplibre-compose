package org.maplibre.compose.mlnffi

import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest

@OptIn(ExperimentalTestApi::class)
internal actual fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  FfiTestPlatform.initialize()
  val watchdog = startHangWatchdog()
  try {
    runAndroidComposeUiTest<ComponentActivity> { block(this) }
  } finally {
    watchdog.interrupt()
    // Bounded, so a stuck stderr dump cannot hold teardown for the watchdog's sake.
    watchdog.join(1_000)
    MlnFfiApplication.resetForTest()
  }
}

@OptIn(ExperimentalTestApi::class)
internal actual fun runPlainComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  runAndroidComposeUiTest<ComponentActivity> { block(this) }
}

/**
 * How long a test may run before the watchdog dumps stacks and kills the process.
 *
 * Compose's `waitUntil` checks its timeout between frame pumps. A blocked native render never
 * reaches that check, so the 30 s timeout never fires and the Android job sits until GitHub cancels
 * it.
 */
private const val HANG_DUMP_DELAY_MILLIS = 50_000L

/** Fails a blocked FFI Compose test so the instrumentation process cannot stay silent. */
private fun startHangWatchdog(): Thread {
  val watchdog = Thread {
    try {
      Thread.sleep(HANG_DUMP_DELAY_MILLIS)
    } catch (_: InterruptedException) {
      return@Thread
    }
    System.err.println(
      "An FFI Compose test has run for $HANG_DUMP_DELAY_MILLIS ms; dumping all threads:"
    )
    for ((thread, stack) in Thread.getAllStackTraces()) {
      System.err.println(thread)
      for (frame in stack) System.err.println("\tat $frame")
    }
    System.err.println(
      "Killing the instrumentation process so a blocked frame pump cannot hold the job."
    )
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
