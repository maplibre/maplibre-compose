package org.maplibre.compose.nucleus

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.TitleBarPlacement
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.material.MaterialDecoratedWindow
import dev.nucleusframework.window.material.MaterialTitleBar
import dev.nucleusframework.window.material.rememberMaterialTitleBarStyle
import org.maplibre.compose.demoapp.DemoApp
import org.maplibre.compose.demoapp.DemoAppTheme
import org.maplibre.compose.demoapp.rememberDemoAppState
import org.maplibre.compose.desktop.ProvideMapPresentationHost

/**
 * The same `DemoApp` the Compose Desktop demo runs, in a Nucleus Tao window instead of an AWT one;
 * the only difference is which `ComposeMapPresentationHost` is in scope.
 */
fun main() {
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
        WindowBackground(MaterialTheme.colorScheme.background)
        WindowAppearance(
          if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            WindowAppearanceMode.Dark
          } else {
            WindowAppearanceMode.Light
          }
        )

        val windowScope = this
        val materialTitleBarStyle = rememberMaterialTitleBarStyle(MaterialTheme.colorScheme)
        val transparentTitleBarStyle =
          remember(materialTitleBarStyle) {
            materialTitleBarStyle.copy(
              colors =
                materialTitleBarStyle.colors.copy(
                  background = Color.Transparent,
                  inactiveBackground = Color.Transparent,
                  border = Color.Transparent,
                  fullscreenControlButtonsBackground = Color.Transparent,
                ),
              metrics =
                materialTitleBarStyle.metrics.copy(
                  height = 24.dp,
                  titlePaneButtonSize = DpSize(24.dp, 24.dp),
                ),
            )
          }
        WindowScaffold(
          titleBar = {
            windowScope.MaterialTitleBar(style = transparentTitleBarStyle)
          },
          titleBarPlacement = TitleBarPlacement.Overlay(),
        ) { chromePadding ->
          val host = rememberTaoComposeMapPresentationHost() ?: return@WindowScaffold
          ProvideMapPresentationHost(host = host) { DemoApp(state, contentPadding = chromePadding) }
        }
      }
    }
  }
}
