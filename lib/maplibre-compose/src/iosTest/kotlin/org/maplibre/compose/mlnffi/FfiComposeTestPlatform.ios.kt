package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest

@OptIn(ExperimentalTestApi::class)
internal actual fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  FfiTestPlatform.initialize()
  try {
    runComposeUiTest { block() }
  } finally {
    MlnFfiApplication.resetForTest()
  }
}

@OptIn(ExperimentalTestApi::class)
internal actual fun ComposeUiTest.setFfiTestMapContent(
  runtimeOptions: MlnFfiRuntimeOptions,
  content: @Composable () -> Unit,
) {
  // The iOS map view builds its own surface controller, like Android's, so no host factory needs
  // to be prepared off the test thread.
  MlnFfiApplication.configure(runtimeOptions)
  setContent(content)
}
