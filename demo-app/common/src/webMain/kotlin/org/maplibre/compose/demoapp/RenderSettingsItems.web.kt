package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.design.FpsCapRow
import org.maplibre.compose.demoapp.design.SwitchRow

@Composable
actual fun RenderSettingsItems(settings: DemoSettings) {
  val options = settings.renderOptions
  FpsCapRow(options.maximumFps) { settings.renderOptions = options.copy(maximumFps = it) }
  SwitchRow("Tile borders", options.isTileBordersEnabled) {
    settings.renderOptions = options.copy(isTileBordersEnabled = it)
  }
  SwitchRow("Collision boxes", options.isCollisionBoxesEnabled) {
    settings.renderOptions = options.copy(isCollisionBoxesEnabled = it)
  }
  SwitchRow("Camera padding", options.isPaddingEnabled) {
    settings.renderOptions = options.copy(isPaddingEnabled = it)
  }
  SwitchRow("Overdraw inspector", options.isOverdrawInspectorEnabled) {
    settings.renderOptions = options.copy(isOverdrawInspectorEnabled = it)
  }
}
