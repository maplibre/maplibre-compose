package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.demoapp.design.SliderListItem
import org.maplibre.compose.demoapp.design.Subheading
import org.maplibre.compose.demoapp.design.SwitchListItem

object RenderOptionsDemo : Demo {
  override val name = "Configure rendering"

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    CardColumn {
      SliderListItem(
        text = "Maximum FPS",
        value = state.renderOptions.maximumFps?.toFloat() ?: 120f,
        onValueChange = { value ->
          state.renderOptions = state.renderOptions.copy(maximumFps = value.roundToInt())
        },
        valueLabel = { it.roundToInt().toString() },
        valueRange = 15f..120f,
        steps = 22,
      )
    }

    Subheading("Debug options")

    CardColumn {
      SwitchListItem(
        text = "Show Tile Boundaries",
        checked = state.renderOptions.debugSettings.isTileBoundariesEnabled,
        onCheckedChange = { isChecked ->
          state.renderOptions =
            state.renderOptions.copy(
              debugSettings =
                state.renderOptions.debugSettings.copy(isTileBoundariesEnabled = isChecked)
            )
        },
      )

      SwitchListItem(
        text = "Show Tile Info",
        checked = state.renderOptions.debugSettings.isTileInfoEnabled,
        onCheckedChange = { isChecked ->
          state.renderOptions =
            state.renderOptions.copy(
              debugSettings = state.renderOptions.debugSettings.copy(isTileInfoEnabled = isChecked)
            )
        },
      )

      SwitchListItem(
        text = "Show Timestamps",
        checked = state.renderOptions.debugSettings.isTimestampsEnabled,
        onCheckedChange = { isChecked ->
          state.renderOptions =
            state.renderOptions.copy(
              debugSettings =
                state.renderOptions.debugSettings.copy(isTimestampsEnabled = isChecked)
            )
        },
      )

      SwitchListItem(
        text = "Show Collision Boxes",
        checked = state.renderOptions.debugSettings.isCollisionBoxesEnabled,
        onCheckedChange = { isChecked ->
          state.renderOptions =
            state.renderOptions.copy(
              debugSettings =
                state.renderOptions.debugSettings.copy(isCollisionBoxesEnabled = isChecked)
            )
        },
      )

      SwitchListItem(
        text = "Show Overdraw Visualization",
        checked = state.renderOptions.debugSettings.isOverdrawVisualizationEnabled,
        onCheckedChange = { isChecked ->
          state.renderOptions =
            state.renderOptions.copy(
              debugSettings =
                state.renderOptions.debugSettings.copy(isOverdrawVisualizationEnabled = isChecked)
            )
        },
      )
    }
  }
}
