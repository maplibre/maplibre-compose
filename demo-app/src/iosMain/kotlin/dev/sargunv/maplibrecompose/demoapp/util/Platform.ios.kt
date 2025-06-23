package dev.sargunv.maplibrecompose.demoapp.util

import dev.sargunv.maplibrecompose.demoapp.demos.Demo
import dev.sargunv.maplibrecompose.demoapp.demos.OfflineManagerDemo
import dev.sargunv.maplibrecompose.demoapp.demos.RenderOptionsDemo
import platform.UIKit.UIDevice

actual object Platform {
  actual val name = "iOS"

  actual val version = UIDevice.currentDevice.systemVersion

  actual val supportedFeatures = PlatformFeature.Everything

  actual val extraDemos: List<Demo> = listOf(OfflineManagerDemo, RenderOptionsDemo)
}
