package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.resetForTest

@OptIn(ExperimentalTestApi::class)
internal actual fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  FfiTestPlatform.initialize()
  try {
    runComposeUiTest { block() }
  } finally {
    DefaultMapRuntime.resetForTest()
  }
}

@OptIn(ExperimentalTestApi::class)
internal actual fun runPlainComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  runComposeUiTest { block() }
}

@OptIn(ExperimentalTestApi::class)
internal actual fun ComposeUiTest.setFfiTestMapContent(
  runtimeOptions: MapRuntimeOptions,
  presentationCount: Int,
  content: @Composable () -> Unit,
) {
  // The iOS map view builds its own surface controller, like Android's, so no host factory needs
  // to be prepared off the test thread.
  require(presentationCount > 0) { "A map test must prepare at least one presentation" }
  DefaultMapRuntime.configure(runtimeOptions)
  setContent(content)
}
