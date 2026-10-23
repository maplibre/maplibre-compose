package org.maplibre.compose.nucleus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.LocalWindowChromeInsets
import dev.nucleusframework.window.TitleBarPlacement
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.WindowControls
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.windowDragArea
import org.maplibre.compose.demoapp.DemoApp
import org.maplibre.compose.demoapp.DemoAppTheme
import org.maplibre.compose.demoapp.DemoLaunch
import org.maplibre.compose.demoapp.rememberDemoAppState
import org.maplibre.compose.desktop.ProvideMapPresentationHost

/**
 * The same `DemoApp` the Compose Desktop demo runs, in a Nucleus Tao window instead of an AWT one;
 * the only difference is which `ComposeMapPresentationHost` is in scope.
 */
fun main(args: Array<String>) {
  val launch = DemoLaunch.parse(args.toList())
  nucleusApplication(
    args = args,
    // A fixture, not a shipped app: allow parallel launches next to other demos.
    enableSingleInstance = false,
  ) {
    DecoratedWindow(
      onCloseRequest = ::exitApplication,
      title = "MapLibre Compose on Nucleus Tao",
      state = rememberWindowState(size = launch.windowSize ?: DpSize(960.dp, 640.dp)),
    ) {
      val state = rememberDemoAppState(launch.camera)
      DemoAppTheme(state) {
        WindowBackground(MaterialTheme.colorScheme.background)
        WindowAppearance(
          if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            WindowAppearanceMode.Dark
          } else {
            WindowAppearanceMode.Light
          },
        )

        WindowScaffold(
          titleBar = {
            val controlsInsets = LocalWindowChromeInsets.current.controlsInsets
            val controlsAlignment =
              if (controlsInsets.calculateStartPadding(LocalLayoutDirection.current) > 0.dp) {
                Alignment.CenterStart
              } else {
                Alignment.CenterEnd
              }
            Box(Modifier.fillMaxWidth().height(32.dp).windowDragArea()) {
              WindowControls(Modifier.align(controlsAlignment))
            }
          },
          titleBarPlacement = TitleBarPlacement.Overlay(),
        ) { chromePadding ->
          // #region host
          val host = rememberTaoComposeMapPresentationHost() ?: return@WindowScaffold
          ProvideMapPresentationHost(host = host) { DemoApp(state, launch, chromePadding) }
          // #endregion host
        }
      }
    }
  }
}
