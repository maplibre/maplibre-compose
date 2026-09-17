package org.maplibre.compose.demoapp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.skiko.wasm.onWasmReady
import org.maplibre.compose.browser.installMapLibreCompose
import org.maplibre.compose.demoapp.ferry.FerryBoard

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  // Reachable use so production DCE keeps @js-joda/timezone. See JsJodaTimeZone.kt.
  @Suppress("UNUSED_VARIABLE") val keepJsJodaTimeZone = jsJodaTz
  if (window.location.search == "?ferries") {
    val stylesheet = document.createElement("link")
    stylesheet.setAttribute("rel", "stylesheet")
    stylesheet.setAttribute("href", "ferry.css")
    document.head!!.appendChild(stylesheet)
    renderComposable(rootElementId = "ferry-root") { FerryBoard() }
    return
  }
  document.getElementById("ferry-root")?.remove()
  onWasmReady {
    // Must run before Compose builds its renderer, which creates the GPU context maps composite
    // into.
    installMapLibreCompose()
    ComposeViewport(document.body!!) { DemoApp() }
  }
}
