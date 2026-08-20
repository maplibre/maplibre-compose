package org.maplibre.compose.demoapp

import androidx.compose.ui.window.ComposeUIViewController
import org.maplibre.compose.ios.MapLibre
import platform.UIKit.UIViewController

@Suppress("unused", "FunctionName") // called in Swift
fun MainViewController(): UIViewController {
  MapLibre.configure()
  return ComposeUIViewController { DemoApp() }
}
