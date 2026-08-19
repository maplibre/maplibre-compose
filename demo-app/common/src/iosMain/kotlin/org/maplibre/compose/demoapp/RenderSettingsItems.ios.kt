package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.design.FpsCapRow
import org.maplibre.compose.demoapp.design.SwitchRow

@Composable
actual fun RenderSettingsItems(settings: DemoSettings) {
  val options = settings.renderOptions
  val debug = options.debugSettings
  FpsCapRow(options.maximumFps) { settings.renderOptions = options.copy(maximumFps = it) }
  SwitchRow("Tile boundaries", debug.isTileBoundariesEnabled) {
    settings.renderOptions = options.copy(debugSettings = debug.copy(isTileBoundariesEnabled = it))
  }
  SwitchRow("Tile info", debug.isTileInfoEnabled) {
    settings.renderOptions = options.copy(debugSettings = debug.copy(isTileInfoEnabled = it))
  }
  SwitchRow("Tile timestamps", debug.isTimestampsEnabled) {
    settings.renderOptions = options.copy(debugSettings = debug.copy(isTimestampsEnabled = it))
  }
  SwitchRow("Collision boxes", debug.isCollisionBoxesEnabled) {
    settings.renderOptions = options.copy(debugSettings = debug.copy(isCollisionBoxesEnabled = it))
  }
  SwitchRow("Overdraw visualization", debug.isOverdrawVisualizationEnabled) {
    settings.renderOptions =
      options.copy(debugSettings = debug.copy(isOverdrawVisualizationEnabled = it))
  }
}
