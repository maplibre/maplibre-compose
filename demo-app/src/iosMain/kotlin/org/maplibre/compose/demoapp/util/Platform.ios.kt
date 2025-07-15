package org.maplibre.compose.demoapp.util

import org.maplibre.compose.demoapp.demos.Demo
import org.maplibre.compose.demoapp.demos.OfflineManagerDemo
import org.maplibre.compose.demoapp.demos.RenderOptionsDemo
import platform.UIKit.UIDevice

actual object Platform {
  actual val name = "iOS"

  actual val version = UIDevice.currentDevice.systemVersion

  actual val supportedFeatures = PlatformFeature.Everything

  actual val extraDemos: List<Demo> = listOf(OfflineManagerDemo, RenderOptionsDemo)
}
