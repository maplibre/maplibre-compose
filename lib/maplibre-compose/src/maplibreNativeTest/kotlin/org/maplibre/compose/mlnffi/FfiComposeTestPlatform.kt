package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseInjectionScope
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import org.maplibre.compose.map.MapRuntimeOptions

/** Runs a Compose UI test using the platform runner that can host a real FFI map. */
@ExperimentalTestApi
internal expect fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit)

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
  runtimeOptions: MapRuntimeOptions,
  presentationCount: Int = 1,
  content: @Composable () -> Unit,
)

// Desktop injects synchronously on its caller. Resolve and inject on the UI thread after
// settling; suppress the redundant implicit wait so input cannot block UI dispatch.
@ExperimentalTestApi
internal fun ComposeUiTest.performMouseInputOnUiThread(
  node: SemanticsNodeInteraction,
  block: MouseInjectionScope.() -> Unit,
) {
  waitForIdle()
  runOnUiThread { runWithoutImplicitWait { node.performMouseInput(block) } }
}

@ExperimentalTestApi
internal fun ComposeUiTest.performTouchInputOnUiThread(
  node: SemanticsNodeInteraction,
  block: TouchInjectionScope.() -> Unit,
) {
  waitForIdle()
  runOnUiThread { runWithoutImplicitWait { node.performTouchInput(block) } }
}
