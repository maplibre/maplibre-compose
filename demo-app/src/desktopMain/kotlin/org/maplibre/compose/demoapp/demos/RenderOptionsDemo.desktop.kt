package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.demoapp.design.FrameRateListItem
import org.maplibre.compose.demoapp.design.SliderListItem
import org.maplibre.compose.demoapp.design.SwitchListItem

object RenderOptionsDemo : Demo {
  override val name = "Configure rendering"

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
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

      // Desktop exposes MapLibre's debug overlays individually rather than as one switch, because
      // the FFI takes them as a set of flags and they are useful separately when debugging tiles.
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
