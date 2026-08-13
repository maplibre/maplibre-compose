package org.maplibre.compose.demoapp.util

import org.maplibre.compose.demoapp.demos.Demo
import org.maplibre.compose.demoapp.demos.GestureOptionsDemo
import org.maplibre.compose.demoapp.demos.OfflineManagerDemo
import org.maplibre.compose.demoapp.demos.RenderOptionsDemo
import org.maplibre.compose.demoapp.demos.SynchronousGeoJsonUpdatesDemo

actual object Platform {
  actual val name = System.getProperty("os.name")!!

  actual val version = System.getProperty("os.version")!!

  actual val extraDemos: List<Demo> =
    listOf(
      GestureOptionsDemo,
      RenderOptionsDemo,
      SynchronousGeoJsonUpdatesDemo,
      OfflineManagerDemo,
    )
}
