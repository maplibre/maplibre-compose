package org.maplibre.compose.nucleus

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import org.maplibre.compose.demoapp.DemoApp
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.compose.desktop.MapLibre
import org.maplibre.compose.desktop.ProvideMapHost
import org.maplibre.compose.desktop.desktopCachePath

/**
 * The same `DemoApp` the Compose Desktop demo runs, in a Nucleus Tao window instead of an AWT one;
 * the only difference is which `ComposeGpuHost` is in scope.
 *
 * Run it with `mise run demo:desktop-nucleus`. On macOS the launcher must pass
 * `-XstartOnFirstThread`; the Gradle task does.
 */
fun main() {
  MapLibre.configure(
    DesktopRuntimeOptions(cachePath = desktopCachePath("org.maplibre.compose.nucleus-fixture"))
  )
  nucleusApplication(
    backend = NucleusBackend.Tao,
    // A fixture, not a shipped app: allow parallel launches next to other demos.
    enableSingleInstance = false,
  ) {
    DecoratedWindow(
      onCloseRequest = ::exitApplication,
      title = "MapLibre Compose on Nucleus Tao",
      state = rememberWindowState(size = DpSize(960.dp, 640.dp)),
    ) {
      val host = rememberTaoComposeGpuHost() ?: return@DecoratedWindow
      ProvideMapHost(host = host) { DemoApp() }
    }
  }
}
