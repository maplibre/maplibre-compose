package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.RenderOptions

/** App-wide diagnostics and toggles, available regardless of which demo is open. */
@Stable
class DemoSettings {
  var gestureOptions by mutableStateOf(GestureOptions.Standard)
  var renderOptions by mutableStateOf(RenderOptions.Standard)
  var showFpsOverlay by mutableStateOf(false)
  var showCameraOverlay by mutableStateOf(false)
  var useMaterial3Controls by mutableStateOf(true)
}

@Composable fun rememberDemoSettings() = remember { DemoSettings() }

/**
 * The gesture toggles this platform's [GestureOptions] offers, as settings list items. An expect,
 * because each platform integration exposes its own set.
 */
@Composable expect fun GestureSettingsItems(settings: DemoSettings)

/**
 * The rendering toggles this platform's [RenderOptions] offers — debug flags, the frame rate cap,
 * and where supported the texture-versus-surface choice — as settings list items.
 */
@Composable expect fun RenderSettingsItems(settings: DemoSettings)
