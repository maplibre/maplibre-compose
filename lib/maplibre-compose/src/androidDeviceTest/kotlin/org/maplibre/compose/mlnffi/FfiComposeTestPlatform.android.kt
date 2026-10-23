package org.maplibre.compose.mlnffi

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.resetForTest

@OptIn(ExperimentalTestApi::class)
internal actual fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  FfiTestPlatform.initialize()
  var failure: Throwable? = null
  try {
    runAndroidComposeUiTest<ComponentActivity> { block(this) }
  } catch (error: Throwable) {
    failure = error
    throw error
  } finally {
    DefaultMapRuntime.resetForTest(failure)
  }
}

@OptIn(ExperimentalTestApi::class)
internal actual fun runPlainComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  runAndroidComposeUiTest<ComponentActivity> { block(this) }
}

@OptIn(ExperimentalTestApi::class)
internal actual fun ComposeUiTest.setFfiTestMapContent(
  runtimeOptions: MapRuntimeOptions,
  presentationCount: Int,
  content: @Composable () -> Unit,
) {
  require(presentationCount > 0) { "A map test must prepare at least one presentation" }
  DefaultMapRuntime.configure(from = runtimeOptions)
  setContent(content)
}
