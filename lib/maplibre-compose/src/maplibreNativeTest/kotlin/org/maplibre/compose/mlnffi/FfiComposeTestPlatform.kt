package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi

/** Runs a Compose UI test using the platform runner that can host a real FFI map. */
@ExperimentalTestApi
internal expect fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit)

/**
 * Configures the application cache and installs map [content] with a test render host prepared off
 * the UI thread.
 */
@ExperimentalTestApi
internal expect fun ComposeUiTest.setFfiTestMapContent(
  runtimeOptions: MlnFfiRuntimeOptions,
  content: @Composable () -> Unit,
)
