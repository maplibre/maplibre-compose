@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import org.jetbrains.skiko.wasm.onWasmReady
import org.maplibre.compose.browser.installMaplibreCompose

// Wrapped in an object so this main() doesn't compete with the demo's entry point.
@OptIn(ExperimentalComposeUiApi::class)
private object BrowserEntryPoint {
  // #region main
  fun main() {
    onWasmReady {
      installMaplibreCompose()
      ComposeViewport(document.body!!) { App() }
    }
  }

  // #endregion main

  @Composable private fun App() = Unit
}
