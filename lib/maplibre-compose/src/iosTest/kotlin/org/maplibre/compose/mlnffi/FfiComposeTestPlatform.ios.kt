package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.maplibre.compose.map.ProcessNativeMapRuntime

@OptIn(ExperimentalTestApi::class)
internal actual fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  FfiTestPlatform.initialize()
  try {
    runComposeUiTest {
      try {
        block()
      } finally {
        disposeFfiTestContent()
      }
    }
  } finally {
    ProcessNativeMapRuntime.resetForTest()
    MlnFfiApplication.resetForTest()
  }
}

internal actual fun pingFfiTestHangWatchdog(timeoutMillis: Long) {
  // iOS has no hang watchdog. A blocked native pump still fails the XCTest timeout.
}

@OptIn(ExperimentalTestApi::class)
internal actual fun runPlainComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  runComposeUiTest { block() }
}

@OptIn(ExperimentalTestApi::class)
internal actual fun ComposeUiTest.setFfiTestMapContent(
  runtimeOptions: MlnFfiRuntimeOptions,
  presentationCount: Int,
  content: @Composable () -> Unit,
) {
  // The iOS map view builds its own surface controller, like Android's, so no host factory needs
  // to be prepared off the test thread.
  require(presentationCount > 0) { "A map test must prepare at least one presentation" }
  MlnFfiApplication.configure(runtimeOptions)
  setContent(content)
}
