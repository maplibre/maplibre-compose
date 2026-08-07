package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi

/** Runs a Compose UI test using the platform runner that can host a real FFI map. */
@ExperimentalTestApi
internal expect fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit)

/**
 * Installs map [content] with platform runtime options and a test render host prepared off the UI
 * thread.
 */
@ExperimentalTestApi
internal expect fun ComposeUiTest.setFfiTestMapContent(
  runtimeOptions: MlnFfiRuntimeOptions,
  content: @Composable () -> Unit,
)
