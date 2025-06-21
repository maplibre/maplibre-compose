package dev.sargunv.maplibrecompose.demoapp.util

import dev.sargunv.maplibrecompose.demoapp.demos.Demo

expect object Platform {
  val name: String

  val version: String

  val supportedFeatures: Set<PlatformFeature>

  val extraDemos: List<Demo>
}
