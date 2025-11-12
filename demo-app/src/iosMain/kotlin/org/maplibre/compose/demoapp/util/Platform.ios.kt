package org.maplibre.compose.demoapp.util

import androidx.compose.foundation.layout.PaddingValues
import org.maplibre.compose.demoapp.demos.Demo
import org.maplibre.compose.demoapp.demos.GestureOptionsDemo
import org.maplibre.compose.demoapp.demos.OfflineManagerDemo
import org.maplibre.compose.demoapp.demos.OrnamentOptionsDemo
import org.maplibre.compose.demoapp.demos.RenderOptionsDemo
import org.maplibre.compose.map.OrnamentOptions
import platform.UIKit.UIDevice

actual object Platform {
  actual val name = "iOS"

  actual val version = UIDevice.currentDevice.systemVersion

  actual val supportedFeatures = PlatformFeature.Everything

  actual val extraDemos: List<Demo> =
    listOf(GestureOptionsDemo, OrnamentOptionsDemo, OfflineManagerDemo, RenderOptionsDemo)

  actual fun padOrnaments(options: OrnamentOptions, padding: PaddingValues) =
    options.copy(padding = padding)
}
