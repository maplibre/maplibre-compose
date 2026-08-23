package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.map.RenderOptions

@Composable
actual fun RenderModeItem(settings: DemoSettings) {
  val options = settings.renderOptions
  SegmentedRow(
    label = "Render mode",
    options = RenderOptions.RenderMode.entries,
    selected = options.preferredRenderMode,
    optionLabel = { it.name },
    onSelect = { settings.renderOptions = options.copy(preferredRenderMode = it) },
  )
}
