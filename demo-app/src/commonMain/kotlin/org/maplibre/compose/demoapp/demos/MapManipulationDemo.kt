package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.MapPosition
import org.maplibre.compose.demoapp.MapSize
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.demoapp.design.GridSelectorListItem
import org.maplibre.compose.demoapp.design.SegmentedButtonListItem
import org.maplibre.compose.demoapp.design.Subheading
import org.maplibre.compose.demoapp.design.SwitchListItem

object MapManipulationDemo : Demo {
  override val name = "Map Manipulation"

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    val manipulationState = state.mapManipulationState

    CardColumn {
      SwitchListItem(
        text = "Show Map",
        checked = manipulationState.isVisible,
        onCheckedChange = { manipulationState.isVisible = it },
      )
    }

    CardColumn {
      SegmentedButtonListItem(
        options = MapSize.entries,
        selectedOption = manipulationState.size,
        onOptionSelected = { manipulationState.size = it },
        optionLabel = { size ->
          when (size) {
            MapSize.Full -> "Full"
            MapSize.Half -> "Half"
            MapSize.Fixed -> "Fixed"
          }
        },
      )
    }

    Subheading(text = "Position")
    CardColumn {
      val positionRows =
        listOf(
          listOf(MapPosition.TopLeft, MapPosition.TopCenter, MapPosition.TopRight),
          listOf(MapPosition.CenterLeft, MapPosition.Center, MapPosition.CenterRight),
          listOf(MapPosition.BottomLeft, MapPosition.BottomCenter, MapPosition.BottomRight),
        )

      GridSelectorListItem(
        options = positionRows,
        selectedOption = manipulationState.position,
        onOptionSelected = { manipulationState.position = it },
        optionLabel = { position -> getPositionLabel(position) },
      )
    }
  }

  private fun getPositionLabel(position: MapPosition): String {
    return when (position) {
      MapPosition.TopLeft -> "↖"
      MapPosition.TopCenter -> "↑"
      MapPosition.TopRight -> "↗"
      MapPosition.CenterLeft -> "←"
      MapPosition.Center -> "●"
      MapPosition.CenterRight -> "→"
      MapPosition.BottomLeft -> "↙"
      MapPosition.BottomCenter -> "↓"
      MapPosition.BottomRight -> "↘"
    }
  }
}
