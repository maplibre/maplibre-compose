package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi

/** Runs a Compose UI test using the platform runner that can host a real FFI map. */
@ExperimentalTestApi
internal expect fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit)

/**
 * Extends the hang watchdog so a long test with many waits is not killed from process start.
 *
 * Android and Desktop implement a deadline. iOS has no watchdog.
 */
internal expect fun pingFfiTestHangWatchdog(timeoutMillis: Long = 50_000L)

/**
 * Replaces map content with an empty tree so [MlnFfiApplication.resetForTest] is not racing a
 * surface.
 */
@ExperimentalTestApi
internal fun ComposeUiTest.disposeFfiTestContent() {
  try {
    setContent {}
  } catch (error: IllegalStateException) {
    if (!isComposeAlreadyFinished(error)) throw error
  }
}

private fun isComposeAlreadyFinished(error: IllegalStateException): Boolean {
  val message = error.message.orEmpty()
  return message.contains("already", ignoreCase = true) ||
    message.contains("finished", ignoreCase = true)
}

/**
 * Runs a Compose UI test that does not create a MapLibre runtime or render host.
 *
 * Recognition tests belong here. A blocked native frame pump never reaches the
 * [ComposeUiTest.waitUntil] timeout. Those cases must not create a map.
 */
@ExperimentalTestApi
internal expect fun runPlainComposeUiTest(block: suspend ComposeUiTest.() -> Unit)

/**
 * Configures the application cache and installs map [content] with a test render host prepared off
 * the UI thread.
 */
@ExperimentalTestApi
internal expect fun ComposeUiTest.setFfiTestMapContent(
  runtimeOptions: MlnFfiRuntimeOptions,
  presentationCount: Int = 1,
  content: @Composable () -> Unit,
)
