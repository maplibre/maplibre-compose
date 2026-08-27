package org.maplibre.compose.demoapp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import org.jetbrains.skiko.wasm.onWasmReady
import org.maplibre.compose.browser.MapLibre

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  // Reachable use so production DCE keeps @js-joda/timezone. See JsJodaTimeZone.kt.
  @Suppress("UNUSED_VARIABLE") val keepJsJodaTimeZone = jsJodaTz
  onWasmReady {
    // Must run before Compose builds its renderer, which creates the GPU context maps composite
    // into.
    MapLibre.configure()
    ComposeViewport(document.body!!) { DemoApp() }
  }
}
