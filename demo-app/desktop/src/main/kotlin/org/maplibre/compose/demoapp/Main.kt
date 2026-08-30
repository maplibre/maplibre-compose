package org.maplibre.compose.demoapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.singleWindowApplication
import org.maplibre.compose.demoapp.agent.LocalAgentScreenshotRecorder
import org.maplibre.compose.demoapp.agent.rememberAgentScreenshotRecorder
import org.maplibre.compose.desktop.ProvideMapPresentationHost
import org.maplibre.compose.desktop.rememberAwtComposeMapPresentationHost

// #region main
fun main() {
  singleWindowApplication {
    val host = rememberAwtComposeMapPresentationHost(window)
    val screenshotRecorder = rememberAgentScreenshotRecorder(host::runOnGpuThread)
    CompositionLocalProvider(LocalAgentScreenshotRecorder provides screenshotRecorder) {
      ProvideMapPresentationHost(host = host) {
        Box(Modifier.fillMaxSize().then(screenshotRecorder.modifier)) { DemoApp() }
      }
    }
  }
}

// #endregion main
