package org.maplibre.compose.nucleus

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import org.maplibre.compose.demoapp.DemoApp
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
    DecoratedWindow(
      onCloseRequest = ::exitApplication,
      title = "MapLibre Compose on Nucleus Tao",
      state = rememberWindowState(size = DpSize(960.dp, 640.dp)),
    ) {
      val host = rememberTaoComposeMapHost() ?: return@DecoratedWindow
      ProvideMapHost(host = host) { DemoApp() }
    }
  }
}
