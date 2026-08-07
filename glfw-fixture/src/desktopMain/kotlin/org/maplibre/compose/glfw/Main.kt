package org.maplibre.compose.glfw

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.sargunv.composeglfw.LocalWindow
import dev.sargunv.composeglfw.Window
import dev.sargunv.composeglfw.glfwApplication
import dev.sargunv.composeglfw.rememberWindowState
import org.maplibre.compose.demoapp.DemoApp
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.compose.desktop.ProvideMapHost
import org.maplibre.compose.desktop.desktopCachePath

/**
 * The same `DemoApp` the Compose Desktop demo runs, in a GLFW window instead of an AWT one; the
 * only difference is which `ComposeGpuHost` is in scope.
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
    LogGlfwScale()
    ProvideMapHost(
      host = rememberGlfwComposeGpuHost(),
      runtimeOptions =
        DesktopRuntimeOptions(cachePath = desktopCachePath("org.maplibre.compose.glfw-fixture")),
    ) {
      DemoApp()
    }
  }
}

/** Logs every value involved in turning GLFW window units into MapLibre render-target pixels. */
@Composable
private fun LogGlfwScale() {
  val host = LocalWindow.current.info
  val compose = LocalWindowInfo.current
  val density = LocalDensity.current.density
  val framebufferScaleX = host.framebufferWidth.toDouble() / host.windowWidth.coerceAtLeast(1)
  val framebufferScaleY = host.framebufferHeight.toDouble() / host.windowHeight.coerceAtLeast(1)

  LaunchedEffect(host, compose.containerSize, density) {
    println(
      "MapLibre GLFW scale: server=${host.displayServer}, " +
        "window=${host.windowWidth}x${host.windowHeight} logical, " +
        "framebuffer=${host.framebufferWidth}x${host.framebufferHeight} physical, " +
        "framebufferScale=${"%.3f".format(framebufferScaleX)}x${"%.3f".format(framebufferScaleY)}, " +
        "contentScale=${"%.3f".format(host.contentScale)}, " +
        "composeDensity=${"%.3f".format(density)}, " +
        "composeContainer=${compose.containerSize.width}x${compose.containerSize.height} px"
    )
  }
}
