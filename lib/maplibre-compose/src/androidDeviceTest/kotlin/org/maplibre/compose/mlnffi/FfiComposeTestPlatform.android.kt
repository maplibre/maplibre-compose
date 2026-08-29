package org.maplibre.compose.mlnffi

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest

@OptIn(ExperimentalTestApi::class)
internal actual fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  FfiTestPlatform.initialize()
  try {
    runAndroidComposeUiTest<ComponentActivity> { block(this) }
  } finally {
    MlnFfiApplication.resetForTest()
  }
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
