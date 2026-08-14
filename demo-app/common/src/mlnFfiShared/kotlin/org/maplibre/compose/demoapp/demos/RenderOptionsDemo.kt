package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.demoapp.design.FrameRateListItem
import org.maplibre.compose.demoapp.design.SegmentedButtonListItem
import org.maplibre.compose.demoapp.design.SliderListItem
import org.maplibre.compose.demoapp.design.Subheading
import org.maplibre.compose.demoapp.design.SwitchListItem
import org.maplibre.compose.demoapp.util.Platform
import org.maplibre.compose.map.RenderOptions

object RenderOptionsDemo : Demo {
  override val name = "Configure rendering"

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    if (Platform.name == "Android") {
      Subheading("Map surface")
      CardColumn {
        SegmentedButtonListItem(
          options = listOf(RenderOptions.RenderMode.Texture, RenderOptions.RenderMode.Surface),
          selectedOption = state.renderOptions.preferredRenderMode,
          onOptionSelected = { mode ->
            state.renderOptions = state.renderOptions.copy(preferredRenderMode = mode)
          },
          optionLabel = { mode ->
            when (mode) {
              RenderOptions.RenderMode.Texture -> "Texture"
              RenderOptions.RenderMode.Surface -> "Surface"
            }
          },
        )
      }
    }

    CardColumn {
      FrameRateListItem(state.frameRateState)

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
        text = "Tile borders",
        checked = state.renderOptions.isTileBordersEnabled,
        onCheckedChange = {
          state.renderOptions = state.renderOptions.copy(isTileBordersEnabled = it)
        },
      )

      SwitchListItem(
        text = "Tile timestamps",
        checked = state.renderOptions.isTileTimestampsEnabled,
        onCheckedChange = {
          state.renderOptions = state.renderOptions.copy(isTileTimestampsEnabled = it)
        },
      )

      SwitchListItem(
        text = "Collision boxes",
        checked = state.renderOptions.isCollisionBoxesEnabled,
        onCheckedChange = {
          state.renderOptions = state.renderOptions.copy(isCollisionBoxesEnabled = it)
        },
      )

      SwitchListItem(
        text = "Tile parse status",
        checked = state.renderOptions.isTileParseStatusEnabled,
        onCheckedChange = {
          state.renderOptions = state.renderOptions.copy(isTileParseStatusEnabled = it)
        },
      )
    }
  }
}
