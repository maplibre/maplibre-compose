@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.maplibre.compose.macosdemo

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import org.maplibre.compose.demoapp.DemoApp
import org.maplibre.compose.demoapp.DemoLaunch
import org.maplibre.compose.macos.ProvideMapPresentationHost
import org.maplibre.compose.map.DefaultMapRuntime
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSWindowWillCloseNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo

fun main() {
  val application = NSApplication.sharedApplication()
  application.setActivationPolicy(
    NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular
  )
  val launch = DemoLaunch.parse(NSProcessInfo.processInfo.arguments.drop(1).map { it as String })
  var open by mutableStateOf(true)
  Window("MapLibre Compose — macOS Native", launch.windowSize ?: DpSize(1100.dp, 760.dp)) {
    DisposableEffect(window) {
      val observer =
        NSNotificationCenter.defaultCenter.addObserverForName(
          NSWindowWillCloseNotification,
          window,
          NSOperationQueue.mainQueue,
        ) {
          open = false
        }
      onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
    if (open) {
      ProvideMapPresentationHost(window) { DemoApp(launch) }
    } else {
      // Remove the map composition before terminating the stock experimental Window's event loop.
      LaunchedEffect(Unit) {
        DefaultMapRuntime.instance.close()
        DefaultMapRuntime.instance.awaitClosed()
        println("MapLibre native demo shutdown complete")
        application.terminate(null)
      }
    }
  }
  application.activateIgnoringOtherApps(true)
  application.run()
}
