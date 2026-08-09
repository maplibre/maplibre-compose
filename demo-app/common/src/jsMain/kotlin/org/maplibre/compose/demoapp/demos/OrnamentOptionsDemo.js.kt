package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.demoapp.design.SwitchListItem

object OrnamentOptionsDemo : Demo {
  override val name = "Configure ornaments"

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    val ornamentState = state.ornamentOptionsState
    val ornamentOptions = state.ornamentOptions

    CardColumn {
      SwitchListItem(
        text = "Material3 controls",
        checked = ornamentState.isMaterial3ControlsEnabled,
        onCheckedChange = { isChecked -> ornamentState.isMaterial3ControlsEnabled = isChecked },
      )
    }

    CardColumn {
      SwitchListItem(
        text = "Logo",
        checked = ornamentOptions.isLogoEnabled,
        enabled = !ornamentState.isMaterial3ControlsEnabled,
        onCheckedChange = { isChecked ->
          state.ornamentOptions = ornamentOptions.copy(isLogoEnabled = isChecked)
        },
      )

      SwitchListItem(
        text = "Attribution",
        checked = ornamentOptions.isAttributionEnabled,
        enabled = !ornamentState.isMaterial3ControlsEnabled,
        onCheckedChange = { isChecked ->
          state.ornamentOptions = ornamentOptions.copy(isAttributionEnabled = isChecked)
        },
      )

      SwitchListItem(
        text = "Navigation",
        checked = ornamentOptions.isNavigationEnabled,
        enabled = !ornamentState.isMaterial3ControlsEnabled,
        onCheckedChange = { isChecked ->
          state.ornamentOptions = ornamentOptions.copy(isNavigationEnabled = isChecked)
        },
      )

      SwitchListItem(
        text = "Scale bar",
        checked = ornamentOptions.isScaleBarEnabled,
        enabled = !ornamentState.isMaterial3ControlsEnabled,
        onCheckedChange = { isChecked ->
          state.ornamentOptions = ornamentOptions.copy(isScaleBarEnabled = isChecked)
        },
      )

      SwitchListItem(
        text = "Globe button",
        checked = ornamentOptions.isGlobeButtonEnabled,
        enabled = !ornamentState.isMaterial3ControlsEnabled,
        onCheckedChange = { isChecked ->
          state.ornamentOptions = ornamentOptions.copy(isGlobeButtonEnabled = isChecked)
        },
      )
    }
  }
}
