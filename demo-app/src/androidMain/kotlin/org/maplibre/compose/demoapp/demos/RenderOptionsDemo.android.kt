package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.demoapp.design.SegmentedButtonListItem
import org.maplibre.compose.demoapp.design.SliderListItem
import org.maplibre.compose.demoapp.design.SwitchListItem
import org.maplibre.compose.map.RenderOptions

object RenderOptionsDemo : Demo {
  override val name = "Configure rendering"

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    CardColumn {
      SegmentedButtonListItem(
        options = RenderOptions.RenderMode.entries,
        selectedOption = state.renderOptions.renderMode,
        onOptionSelected = { option ->
          state.renderOptions = state.renderOptions.copy(renderMode = option)
        },
      )

      SliderListItem(
        text = "Maximum FPS",
        value = state.renderOptions.maximumFps?.toFloat() ?: 120f,
        onValueChange = { value ->
          state.renderOptions = state.renderOptions.copy(maximumFps = value.roundToInt())
        },
        valueLabel = { it.roundToInt().toString() },
        valueRange = 15f..120f,
        steps = 20,
      )

      SwitchListItem(
        text = "Enable Debug Overlays",
        checked = state.renderOptions.isDebugEnabled,
        onCheckedChange = { isChecked ->
          state.renderOptions = state.renderOptions.copy(isDebugEnabled = isChecked)
        },
      )
    }
  }
}
