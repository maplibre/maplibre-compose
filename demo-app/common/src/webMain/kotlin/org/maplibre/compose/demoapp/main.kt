package org.maplibre.compose.demoapp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import org.jetbrains.skiko.wasm.onWasmReady
import org.maplibre.compose.browser.installMaplibreCompose

@OptIn(ExperimentalComposeUiApi::class)
internal fun startDemo() {
  // Reachable use so production DCE keeps @js-joda/timezone. See JsJodaTimeZone.kt.
  @Suppress("UNUSED_VARIABLE") val keepJsJodaTimeZone = jsJodaTz
  document.getElementById("ferry-root")?.remove()
  onWasmReady {
    installMaplibreCompose()
    val launch = DemoLaunch.parse(::launchParameter)
    ComposeViewport(document.body!!) { DemoApp(launch) }
  }
}

private fun launchParameter(name: String): String? =
  js("new URLSearchParams(window.location.search).get(name)")
