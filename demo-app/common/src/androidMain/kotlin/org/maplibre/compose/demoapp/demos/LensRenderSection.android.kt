package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.map.RenderOptions

@Composable
actual fun LensRenderSection(lensOptions: RenderOptions, onLensChange: (RenderOptions) -> Unit) {
  SectionHeader("Rendering")
  SegmentedRow(
    label = "Lens render mode",
    options = RenderOptions.RenderMode.entries,
    selected = lensOptions.preferredRenderMode,
    optionLabel = { it.name },
    onSelect = { onLensChange(lensOptions.copy(preferredRenderMode = it)) },
  )
}
