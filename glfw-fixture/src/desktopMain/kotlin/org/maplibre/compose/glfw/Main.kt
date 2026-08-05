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
 * The same `DemoApp` the Compose Desktop demo runs, in a GLFW window instead of an AWT one; the
 * only difference is which `DesktopMapHostFactory` is in scope.
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
