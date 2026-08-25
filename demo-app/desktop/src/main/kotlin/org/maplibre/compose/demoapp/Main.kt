package org.maplibre.compose.demoapp

import androidx.compose.ui.window.singleWindowApplication
import org.maplibre.compose.desktop.ProvideMapHost
import org.maplibre.compose.desktop.rememberAwtComposeMapHost

// #region main
fun main() {
  singleWindowApplication {
    ProvideMapHost(host = rememberAwtComposeMapHost(window)) { DemoApp() }
  }
}

// #endregion main
