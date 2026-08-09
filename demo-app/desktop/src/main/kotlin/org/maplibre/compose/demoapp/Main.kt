package org.maplibre.compose.demoapp

import androidx.compose.ui.window.singleWindowApplication
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.compose.desktop.MapLibre
import org.maplibre.compose.desktop.ProvideMapHost
import org.maplibre.compose.desktop.desktopCachePath
import org.maplibre.compose.desktop.rememberAwtComposeGpuHost

// #region main
fun main() {
  MapLibre.configure(
    DesktopRuntimeOptions(cachePath = desktopCachePath("org.maplibre.compose.demoapp"))
  )
  singleWindowApplication { ProvideMapHost(host = rememberAwtComposeGpuHost(window)) { DemoApp() } }
}

// #endregion main
