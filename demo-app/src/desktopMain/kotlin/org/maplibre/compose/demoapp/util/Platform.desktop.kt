package org.maplibre.compose.demoapp.util

import org.maplibre.compose.demoapp.demos.Demo
import org.maplibre.compose.demoapp.demos.GestureOptionsDemo

actual object Platform {
  actual val name = System.getProperty("os.name")!!

  actual val version = System.getProperty("os.version")!!

  actual val supportedFeatures = emptySet<PlatformFeature>()

  actual val extraDemos: List<Demo> = listOf(GestureOptionsDemo)
}
