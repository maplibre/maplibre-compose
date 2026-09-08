package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.map.AndroidRenderMode
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.renderMode

@Composable
actual fun LensRenderSection(lensOptions: MapUiOptions, onLensChange: (MapUiOptions) -> Unit) {
  SectionHeader("Rendering")
  SegmentedRow(
    label = "Lens render mode",
    options = AndroidRenderMode.entries,
    selected = lensOptions.renderMode,
    optionLabel = { it.name },
    onSelect = { mode -> onLensChange(MapUiOptions(lensOptions) { renderMode = mode }) },
  )
}

/** Texture mode, because Android applies Compose modifiers to the map only in texture mode. */
actual val LensUiOptionsDefault: MapUiOptions = MapUiOptions {
  renderMode = AndroidRenderMode.Texture
}
