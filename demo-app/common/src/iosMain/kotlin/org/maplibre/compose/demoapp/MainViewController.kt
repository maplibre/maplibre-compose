package org.maplibre.compose.demoapp

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

@Suppress("unused", "FunctionName") // called in Swift
fun MainViewController(): UIViewController = ComposeUIViewController { DemoApp() }
