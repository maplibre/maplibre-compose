package org.maplibre.compose.demoapp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import org.jetbrains.skiko.wasm.onWasmReady
import org.maplibre.compose.browser.MapLibre

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  onWasmReady {
    // Must run before Compose builds its renderer, which creates the GPU context maps composite
    // into.
    MapLibre.initialize()
    ComposeViewport(document.body!!) { DemoApp() }
  }
}
