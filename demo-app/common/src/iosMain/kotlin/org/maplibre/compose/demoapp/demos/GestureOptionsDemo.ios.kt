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
        checked = state.gestureOptions.isScrollEnabled,
        onCheckedChange = { isChecked ->
          state.gestureOptions = state.gestureOptions.copy(isScrollEnabled = isChecked)
        },
      )

      SwitchListItem(
        text = "Pinch to zoom",
        checked = state.gestureOptions.isZoomEnabled,
        onCheckedChange = { isChecked ->
          state.gestureOptions = state.gestureOptions.copy(isZoomEnabled = isChecked)
        },
      )

      SwitchListItem(
        text = "Two-finger rotate",
        checked = state.gestureOptions.isRotateEnabled,
        onCheckedChange = { isChecked ->
          state.gestureOptions = state.gestureOptions.copy(isRotateEnabled = isChecked)
        },
      )

      SwitchListItem(
        text = "Two-finger tilt",
        checked = state.gestureOptions.isTiltEnabled,
        onCheckedChange = { isChecked ->
          state.gestureOptions = state.gestureOptions.copy(isTiltEnabled = isChecked)
        },
      )

      SwitchListItem(
        text = "Haptic feedback",
        checked = state.gestureOptions.isHapticFeedbackEnabled,
        onCheckedChange = { isChecked ->
          state.gestureOptions = state.gestureOptions.copy(isHapticFeedbackEnabled = isChecked)
        },
      )
    }
  }
}
