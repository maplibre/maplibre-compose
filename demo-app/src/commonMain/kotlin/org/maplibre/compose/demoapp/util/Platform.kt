package org.maplibre.compose.demoapp.util

import org.maplibre.compose.demoapp.demos.Demo

expect object Platform {
  val name: String

  val version: String

  val supportedFeatures: Set<PlatformFeature>

  val extraDemos: List<Demo>
}
