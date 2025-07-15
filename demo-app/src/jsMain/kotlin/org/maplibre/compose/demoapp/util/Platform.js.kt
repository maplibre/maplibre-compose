package org.maplibre.compose.demoapp.util

import kotlinx.browser.window
import org.maplibre.compose.demoapp.demos.Demo

actual object Platform {
  actual val name = "JS on ${window.navigator.appName}"

  actual val version = window.navigator.appVersion

  actual val supportedFeatures = emptySet<PlatformFeature>()

  actual val extraDemos = emptyList<Demo>()
}
