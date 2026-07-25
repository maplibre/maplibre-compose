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
   * Everything the demo gates on.
   *
   * `LayerStyling` works now that sources, layers, and expressions are implemented over MapLibre's
   * JSON style API.
   *
   * `InteropBlending` works because of how the desktop map is composited: MapLibre renders into a
   * texture that Skia draws as part of the Compose scene, so overlays, alpha, and transforms apply
   * to the map like any other composable. That falls out of this architecture rather than being
   * something desktop had to implement — it is the platforms embedding a native map view that
   * struggle here.
   */
  actual val supportedFeatures = PlatformFeature.Everything

  actual val extraDemos: List<Demo> =
    listOf(
      GestureOptionsDemo,
      RenderOptionsDemo,
      // Honored on desktop: mbgl reads synchronousUpdate from the GeoJSON source's own JSON, which
      // is the path the desktop source implementation uses.
      SynchronousGeoJsonUpdatesDemo,
      OfflineManagerDemo,
      // Deliberately absent: OrnamentOptionsDemo, because MapLibre Native's core draws no ornaments
      // and the Material 3 controls replace them.
    )

  /** Desktop draws no ornaments, so there is no padding to apply. */
  actual fun padOrnaments(options: OrnamentOptions, padding: PaddingValues) = options
}
