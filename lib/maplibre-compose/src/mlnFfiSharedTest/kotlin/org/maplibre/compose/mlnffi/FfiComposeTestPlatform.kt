package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi

/** Runs a Compose UI test using the platform runner that can host a real FFI map. */
@ExperimentalTestApi
internal expect fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit)

/** Adds platform runtime options and any test-only render host required around map [content]. */
@Composable
internal expect fun FfiTestMapContent(
  runtimeOptions: MlnFfiRuntimeOptions,
  content: @Composable () -> Unit,
)
