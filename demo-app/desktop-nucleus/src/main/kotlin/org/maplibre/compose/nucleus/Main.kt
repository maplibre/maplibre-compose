package org.maplibre.compose.nucleus

import androidx.compose.material3.Text
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.material.MaterialDecoratedWindow
import dev.nucleusframework.window.material.MaterialTitleBar
import org.maplibre.compose.demoapp.DemoApp
import org.maplibre.compose.demoapp.DemoAppTheme
import org.maplibre.compose.demoapp.rememberDemoAppState
import org.maplibre.compose.desktop.MapLibre
import org.maplibre.compose.desktop.ProvideMapHost

/**
 * The same `DemoApp` the Compose Desktop demo runs, in a Nucleus Tao window instead of an AWT one;
 * the only difference is which `ComposeMapHost` is in scope.
 */
fun main() {
  MapLibre.configure(applicationId = "org.maplibre.compose.nucleus-fixture")
  nucleusApplication(
    backend = NucleusBackend.Tao,
    // A fixture, not a shipped app: allow parallel launches next to other demos.
    enableSingleInstance = false,
  ) {
    val state = rememberDemoAppState()
    DemoAppTheme(state) {
      MaterialDecoratedWindow(
        onCloseRequest = ::exitApplication,
        title = "MapLibre Compose on Nucleus Tao",
        state = rememberWindowState(size = DpSize(960.dp, 640.dp)),
      ) {
        MaterialTitleBar { Text("MapLibre Compose on Nucleus Tao") }
        val host = rememberTaoComposeMapHost() ?: return@MaterialDecoratedWindow
        ProvideMapHost(host = host) { DemoApp(state) }
      }
    }
  }
}
