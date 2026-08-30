package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.singleWindowApplication
import org.maplibre.compose.desktop.MapLibre
import org.maplibre.compose.desktop.ProvideMapPresentationHost
import org.maplibre.compose.desktop.rememberAwtComposeMapPresentationHost

// #region main
fun main() {
  MapLibre.configure(applicationId = "com.example.myapp")
  singleWindowApplication {
    ProvideMapPresentationHost(host = rememberAwtComposeMapPresentationHost(window)) {
      App()
    }
  }
}

// #endregion main

@Composable private fun App() = Unit
