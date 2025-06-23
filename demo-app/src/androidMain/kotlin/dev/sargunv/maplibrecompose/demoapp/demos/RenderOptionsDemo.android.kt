package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sargunv.maplibrecompose.core.RenderOptions
import dev.sargunv.maplibrecompose.demoapp.DemoState
import dev.sargunv.maplibrecompose.demoapp.design.CardColumn
import dev.sargunv.maplibrecompose.demoapp.design.SegmentedButtonListItem
import dev.sargunv.maplibrecompose.demoapp.design.SliderListItem
import dev.sargunv.maplibrecompose.demoapp.design.SwitchListItem
import kotlin.math.roundToInt

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
