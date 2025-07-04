package dev.sargunv.maplibrecompose.demoapp

import androidx.compose.material3.Text
import androidx.compose.ui.window.singleWindowApplication
import dev.sargunv.maplibrecompose.compose.KcefProvider
import dev.sargunv.maplibrecompose.compose.MaplibreContextProvider

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
