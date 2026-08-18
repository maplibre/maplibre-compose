package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.design.FpsCapRow
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.map.RenderOptions

@Composable
actual fun RenderSettingsItems(settings: DemoSettings) {
  val options = settings.renderOptions
  SegmentedRow(
    label = "Render mode",
    options = RenderOptions.RenderMode.entries,
    selected = options.renderMode,
    optionLabel = { it.name },
    onSelect = { settings.renderOptions = options.copy(renderMode = it) },
  )
  FpsCapRow(options.maximumFps) { settings.renderOptions = options.copy(maximumFps = it) }
  SwitchRow("Debug overlays", options.isDebugEnabled) {
    settings.renderOptions = options.copy(isDebugEnabled = it)
  }
}
