package org.maplibre.compose.demoapp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.skiko.wasm.onWasmReady
import org.maplibre.compose.browser.MapLibre
import org.maplibre.compose.snippetdemos.SnippetDemoHost
import org.w3c.dom.url.URLSearchParams

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  // Reachable use so production DCE keeps @js-joda/timezone. See JsJodaTimeZone.kt.
  @Suppress("UNUSED_VARIABLE") val keepJsJodaTimeZone = jsJodaTz
  // The documentation site embeds single demos next to code snippets.
  val snippet = URLSearchParams(window.location.search).get("snippet")
  onWasmReady {
    // Must run before Compose builds its renderer, which creates the GPU context maps composite
    // into.
    MapLibre.configure()
    ComposeViewport(document.body!!) {
      if (snippet != null) SnippetDemoHost(snippet) else DemoApp()
    }
  }
}
