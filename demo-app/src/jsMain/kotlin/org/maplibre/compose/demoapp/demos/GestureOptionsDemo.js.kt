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
        text = "Drag to rotate",
        checked = state.gestureOptions.isDragRotateEnabled,
        onCheckedChange = { isChecked ->
          state.gestureOptions = state.gestureOptions.copy(isDragRotateEnabled = isChecked)
        },
      )

      SwitchListItem(
        text = "Touch pitch/tilt",
        checked = state.gestureOptions.isTouchPitchEnabled,
        onCheckedChange = { isChecked ->
          state.gestureOptions = state.gestureOptions.copy(isTouchPitchEnabled = isChecked)
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
        text = "Scroll wheel to zoom",
        checked = state.gestureOptions.isScrollZoomEnabled,
        onCheckedChange = { isChecked ->
          state.gestureOptions = state.gestureOptions.copy(isScrollZoomEnabled = isChecked)
        },
      )
    }
  }
}
