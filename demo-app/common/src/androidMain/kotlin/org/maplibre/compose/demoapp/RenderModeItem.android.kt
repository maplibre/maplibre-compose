package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.map.AndroidRenderMode
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.renderMode

@Composable
actual fun RenderModeItem(settings: DemoSettings) {
  val options = settings.uiOptions
  SegmentedRow(
    label = "Render mode",
    options = AndroidRenderMode.entries,
    selected = options.renderMode,
    optionLabel = { it.name },
    onSelect = { mode -> settings.uiOptions = MapUiOptions(options) { renderMode = mode } },
  )
}
