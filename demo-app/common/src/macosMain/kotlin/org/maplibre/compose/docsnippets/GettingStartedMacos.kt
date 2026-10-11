package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import org.maplibre.compose.macos.ProvideMapPresentationHost

// #region host
fun openMapWindow() {
  Window("My app", DpSize(800.dp, 600.dp)) {
    ProvideMapPresentationHost(window) { App() }
  }
}

// #endregion host

@Composable private fun App() = Unit
