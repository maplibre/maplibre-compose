package org.maplibre.compose.glfw

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.sargunv.composeglfw.Window
import dev.sargunv.composeglfw.glfwApplication
import dev.sargunv.composeglfw.rememberWindowState
import org.maplibre.compose.demoapp.DemoApp
import org.maplibre.compose.desktop.LocalDesktopMapHostFactory

/**
 * The demo application, in a GLFW window instead of an AWT one.
 *
 * This file is the entire integration. It is the same `DemoApp` the Compose Desktop demo runs,
 * unchanged and unaware of any of this; the only difference between the two is which
 * `DesktopMapHostFactory` is in scope. That is the claim the host SPI makes, and running the whole
 * demo rather than a lone map is what makes the claim worth anything — the style switcher, the
 * gesture demos, and the offline screens all go through this host too.
 *
 * `InstallGlfwMainDispatcher` is the one line that is not about maps at all; see its documentation
 * for why a non-AWT Compose host currently has to supply `Dispatchers.Main` itself.
 *
 * Run it with `./gradlew :glfw-fixture:runGlfwFixture`. On macOS the launcher must pass
 * `-XstartOnFirstThread`; the Gradle task does.
 */
fun main() = glfwApplication {
  Window(
    onCloseRequest = ::exitApplication,
    title = "MapLibre Compose on compose-glfw",
    state = rememberWindowState(size = DpSize(960.dp, 640.dp)),
  ) {
    InstallGlfwMainDispatcher()
    CompositionLocalProvider(
      LocalDesktopMapHostFactory provides rememberGlfwDesktopMapHostFactory()
    ) {
      DemoApp()
    }
  }
}
