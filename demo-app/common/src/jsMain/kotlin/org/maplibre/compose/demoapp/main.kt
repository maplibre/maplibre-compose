package org.maplibre.compose.demoapp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import org.jetbrains.skiko.wasm.onWasmReady
import org.maplibre.compose.browser.MapLibre

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  onWasmReady {
    // Before Compose builds its renderer, which is the moment the GPU context maps composite into
    // is created.
    MapLibre.initialize()
    ComposeViewport(document.body!!) { DemoApp() }
  }
}
