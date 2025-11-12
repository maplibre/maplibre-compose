package org.maplibre.compose.demoapp.util

import androidx.compose.foundation.layout.PaddingValues
import kotlinx.browser.window
import org.maplibre.compose.demoapp.demos.Demo
import org.maplibre.compose.demoapp.demos.GestureOptionsDemo
import org.maplibre.compose.demoapp.demos.OrnamentOptionsDemo
import org.maplibre.compose.map.OrnamentOptions

actual object Platform {
  actual val name = "JS on ${window.navigator.appName}"

  actual val version = window.navigator.appVersion

  actual val supportedFeatures = emptySet<PlatformFeature>()

  actual val extraDemos: List<Demo> = listOf(GestureOptionsDemo, OrnamentOptionsDemo)

  // TODO padding not supported
  actual fun padOrnaments(options: OrnamentOptions, padding: PaddingValues) = options
}
