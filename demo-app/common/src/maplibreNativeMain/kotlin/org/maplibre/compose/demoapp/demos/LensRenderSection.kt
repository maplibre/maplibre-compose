package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.DemoSettings
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.map.RenderOptions

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
