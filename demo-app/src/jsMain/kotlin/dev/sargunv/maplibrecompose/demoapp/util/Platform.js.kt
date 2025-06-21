package dev.sargunv.maplibrecompose.demoapp.util

import dev.sargunv.maplibrecompose.demoapp.demos.Demo
import kotlinx.browser.window

actual object Platform {
  actual val name = "JS on ${window.navigator.appName}"

  actual val version = window.navigator.appVersion

  actual val supportedFeatures = emptySet<PlatformFeature>()

  actual val extraDemos = emptyList<Demo>()
}
