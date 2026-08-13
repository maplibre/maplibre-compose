package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.MapControls
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.demoapp.design.SegmentedButtonListItem
import org.maplibre.compose.demoapp.design.Subheading

object MapControlsDemo : Demo {
  override val name = "Map controls"

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    Subheading(text = "Overlay")
    CardColumn {
      SegmentedButtonListItem(
        options = MapControls.entries,
        selectedOption = state.mapControlsState.controls,
        onOptionSelected = { state.mapControlsState.controls = it },
        optionLabel = { controls ->
          when (controls) {
            MapControls.Foundation -> "Default"
            MapControls.Material3 -> "Material 3"
            MapControls.None -> "None"
          }
        },
      )
    }
  }
}
