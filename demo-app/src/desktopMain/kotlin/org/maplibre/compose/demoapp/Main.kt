package org.maplibre.compose.demoapp

import androidx.compose.material3.Text
import androidx.compose.ui.window.singleWindowApplication
import org.maplibre.compose.map.MaplibreContextProvider
import org.maplibre.compose.util.KcefProvider

// This should enable support for blending Compose over Swing views, but it doesn't seem to work
// with KCEF. Maybe we'll get it working when we integrate MapLibre Native instead.
// System.setProperty("compose.interop.blending", "true")

// -8<- [start:main]
fun main() {
  singleWindowApplication {
    KcefProvider(
      loading = { Text("Performing first time setup ...") },
      content = { MaplibreContextProvider { DemoApp() } },
    )
  }
}

// -8<- [end:main]
