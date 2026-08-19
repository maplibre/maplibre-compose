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
    selected = options.preferredRenderMode,
    optionLabel = { it.name },
    onSelect = { settings.renderOptions = options.copy(preferredRenderMode = it) },
  )
  FpsCapRow(options.maximumFps) { settings.renderOptions = options.copy(maximumFps = it) }
  SwitchRow("Tile borders", options.isTileBordersEnabled) {
    settings.renderOptions = options.copy(isTileBordersEnabled = it)
  }
  SwitchRow("Tile timestamps", options.isTileTimestampsEnabled) {
    settings.renderOptions = options.copy(isTileTimestampsEnabled = it)
  }
  SwitchRow("Tile parse status", options.isTileParseStatusEnabled) {
    settings.renderOptions = options.copy(isTileParseStatusEnabled = it)
  }
  SwitchRow("Collision boxes", options.isCollisionBoxesEnabled) {
    settings.renderOptions = options.copy(isCollisionBoxesEnabled = it)
  }
}
