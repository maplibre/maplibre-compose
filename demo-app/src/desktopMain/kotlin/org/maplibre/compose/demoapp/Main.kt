package org.maplibre.compose.demoapp

import androidx.compose.ui.window.singleWindowApplication
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.compose.desktop.ProvideMapHost
import org.maplibre.compose.desktop.desktopCachePath
import org.maplibre.compose.desktop.rememberAwtComposeGpuHost

// -8<- [start:main]
fun main() {
  singleWindowApplication {
    ProvideMapHost(
      host = rememberAwtComposeGpuHost(window),
      runtimeOptions =
        DesktopRuntimeOptions(cachePath = desktopCachePath("org.maplibre.compose.demoapp")),
    ) {
      DemoApp()
    }
  }
}

// -8<- [end:main]
