package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import org.maplibre.compose.macos.ProvideMapPresentationHost
import platform.AppKit.NSWindow

// #region host
@Composable
fun NativeMacWindowContent(window: NSWindow) {
  ProvideMapPresentationHost(window) { App() }
}

// #endregion host

@Composable private fun App() = Unit
