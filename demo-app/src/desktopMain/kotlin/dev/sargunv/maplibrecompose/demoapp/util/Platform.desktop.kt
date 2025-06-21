package dev.sargunv.maplibrecompose.demoapp.util

import dev.sargunv.maplibrecompose.demoapp.demos.Demo

actual object Platform {
  actual val name = System.getProperty("os.name")!!

  actual val version = System.getProperty("os.version")!!

  actual val supportedFeatures = emptySet<PlatformFeature>()

  actual val extraDemos: List<Demo> = emptyList()
}
