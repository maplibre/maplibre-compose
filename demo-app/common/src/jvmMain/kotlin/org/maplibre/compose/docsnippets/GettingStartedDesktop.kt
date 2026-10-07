package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.singleWindowApplication
import org.maplibre.compose.desktop.ComposeMapPresentationHost
import org.maplibre.compose.desktop.MetalComposeGpuContext
import org.maplibre.compose.desktop.ProvideMapPresentationHost
import org.maplibre.compose.desktop.rememberAwtComposeMapPresentationHost
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

// #region main
fun main() {
  singleWindowApplication {
    ProvideMapPresentationHost(host = rememberAwtComposeMapPresentationHost(window)) {
      App()
    }
  }
}

// #endregion main

@Composable private fun App() = Unit

// #region custom-host
@OptIn(ExperimentalMaplibreComposeApi::class)
fun customMetalHost(
  gpuContext: () -> MetalComposeGpuContext?,
  runOnGpuThread: (Runnable) -> Unit,
): ComposeMapPresentationHost =
  ComposeMapPresentationHost.macosMetal(
    description = "my Metal window",
    gpuContext = gpuContext,
    runOnGpuThread = runOnGpuThread,
  )
// #endregion custom-host
