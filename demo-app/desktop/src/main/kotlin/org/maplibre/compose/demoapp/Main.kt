package org.maplibre.compose.demoapp

import androidx.compose.ui.window.singleWindowApplication
import org.maplibre.compose.desktop.ProvideMapPresentationHost
import org.maplibre.compose.desktop.rememberAwtComposeMapPresentationHost

// #region main
fun main() {
  singleWindowApplication {
    val host = rememberAwtComposeMapPresentationHost(window)
    ProvideMapPresentationHost(host = host) { DemoApp() }
  }
}

// #endregion main
