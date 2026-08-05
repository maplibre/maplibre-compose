package org.maplibre.compose.demoapp.util

import androidx.compose.foundation.layout.PaddingValues
import org.maplibre.compose.demoapp.demos.Demo
import org.maplibre.compose.demoapp.demos.GestureOptionsDemo
import org.maplibre.compose.demoapp.demos.OfflineManagerDemo
import org.maplibre.compose.demoapp.demos.RenderOptionsDemo
import org.maplibre.compose.demoapp.demos.SynchronousGeoJsonUpdatesDemo
import org.maplibre.compose.map.OrnamentOptions

actual object Platform {
  actual val name = System.getProperty("os.name")!!

  actual val version = System.getProperty("os.version")!!

  /**
   * `InteropBlending` works because MapLibre renders into a texture Skia draws as part of the
   * Compose scene, so overlays, alpha, and transforms apply to the map like any other composable.
   */
  actual val supportedFeatures = PlatformFeature.Everything

  actual val extraDemos: List<Demo> =
    listOf(
      GestureOptionsDemo,
      RenderOptionsDemo,
      // Honored on desktop: mbgl reads synchronousUpdate from the GeoJSON source's own JSON.
      SynchronousGeoJsonUpdatesDemo,
      OfflineManagerDemo,
      // Deliberately absent: OrnamentOptionsDemo; MapLibre Native's core draws no ornaments.
    )

  /** Desktop draws no ornaments, so there is no padding to apply. */
  actual fun padOrnaments(options: OrnamentOptions, padding: PaddingValues) = options
}
