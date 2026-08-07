package org.maplibre.compose.demoapp

import androidx.compose.ui.window.singleWindowApplication
import org.maplibre.compose.desktop.ProvideMapHost
import org.maplibre.compose.desktop.rememberAwtComposeGpuHost

// -8<- [start:main]
fun main() {
  singleWindowApplication { ProvideMapHost(rememberAwtComposeGpuHost(window)) { DemoApp() } }
}

// -8<- [end:main]
