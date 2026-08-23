package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.design.FpsCapRow
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.map.RenderOptions

@Composable
actual fun RenderSettingsItems(settings: DemoSettings) {
  val options = settings.renderOptions
  RenderModeRow("Render mode", options) { settings.renderOptions = it }
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

@Composable
actual fun LensRenderSection(
  settings: DemoSettings,
  lensOptions: RenderOptions,
  onLensChange: (RenderOptions) -> Unit,
) {
  SectionHeader("Rendering")
  RenderModeRow("Render mode", settings.renderOptions) { settings.renderOptions = it }
  RenderModeRow("Lens render mode", lensOptions, onLensChange)
}

actual val LensRenderOptionsDefault: RenderOptions =
  RenderOptions.Standard.copy(preferredRenderMode = RenderOptions.RenderMode.Texture)

@Composable
private fun RenderModeRow(
  label: String,
  options: RenderOptions,
  onChange: (RenderOptions) -> Unit,
) {
  SegmentedRow(
    label = label,
    options = RenderOptions.RenderMode.entries,
    selected = options.preferredRenderMode,
    optionLabel = { it.name },
    onSelect = { onChange(options.copy(preferredRenderMode = it)) },
  )
}
