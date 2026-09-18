package org.maplibre.compose.demoapp

import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication
import org.maplibre.compose.desktop.ProvideMapPresentationHost
import org.maplibre.compose.desktop.rememberAwtComposeMapPresentationHost

// #region main
fun main(args: Array<String>) {
  val launch = DemoLaunch.parse(args.toList())
  val windowState = launch.windowSize?.let { WindowState(size = it) } ?: WindowState()
  singleWindowApplication(state = windowState) {
    val host = rememberAwtComposeMapPresentationHost(window)
    ProvideMapPresentationHost(host = host) { DemoApp(launch) }
  }
}

// #endregion main
