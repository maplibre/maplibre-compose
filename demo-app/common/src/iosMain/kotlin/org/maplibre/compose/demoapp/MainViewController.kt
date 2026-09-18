package org.maplibre.compose.demoapp

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

@Suppress("unused", "FunctionName") // called in Swift
fun MainViewController(): UIViewController {
  val launch = DemoLaunch.parse(NSProcessInfo.processInfo.arguments.drop(1).map { it as String })
  return ComposeUIViewController { DemoApp(launch) }
}
