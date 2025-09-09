package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.demoapp.design.SwitchListItem

object GestureOptionsDemo : Demo {
  override val name = "Configure gestures"

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    CardColumn {
      SwitchListItem(
        text = "Drag to pan",
        checked = state.gestureOptions.isDragPanEnabled,
        onCheckedChange = { isChecked ->
          state.gestureOptions = state.gestureOptions.copy(isDragPanEnabled = isChecked)
        },
      )

      SwitchListItem(
        text = "Scroll wheel to zoom",
        checked = state.gestureOptions.isScrollZoomEnabled,
        onCheckedChange = { isChecked ->
          state.gestureOptions = state.gestureOptions.copy(isScrollZoomEnabled = isChecked)
        },
      )

      SwitchListItem(
        text = "Double-click to zoom",
        checked = state.gestureOptions.isDoubleClickZoomEnabled,
        onCheckedChange = { isChecked ->
          state.gestureOptions = state.gestureOptions.copy(isDoubleClickZoomEnabled = isChecked)
        },
      )

      SwitchListItem(
        text = "Drag to rotate/tilt",
        checked = state.gestureOptions.isDragRotateTiltEnabled,
        onCheckedChange = { isChecked ->
          state.gestureOptions = state.gestureOptions.copy(isDragRotateTiltEnabled = isChecked)
        },
      )

      SwitchListItem(
        text = "Arrow keys to pan",
        checked = state.gestureOptions.isKeyboardPanEnabled,
        onCheckedChange = { isChecked ->
          state.gestureOptions = state.gestureOptions.copy(isKeyboardPanEnabled = isChecked)
        },
      )

      SwitchListItem(
        text = "+/- keys to zoom",
        checked = state.gestureOptions.isKeyboardZoomEnabled,
        onCheckedChange = { isChecked ->
          state.gestureOptions = state.gestureOptions.copy(isKeyboardZoomEnabled = isChecked)
        },
      )
    }
  }
}
