package org.maplibre.compose.demoapp.demos

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.material3.LocationLayer

object UserLocationDemo : Demo {
  override val name = "User Location"

  private var locationClickedCount by mutableIntStateOf(0)

  @Composable
  override fun MapContent(state: DemoState, isOpen: Boolean) {
    if (!isOpen) return

    LocationLayer(
      id = "user-location",
      locationState = state.locationState,
      cameraState = state.cameraState,
      accuracyThreshold = 0f,
      onClick = { location -> locationClickedCount++ },
    )
  }

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    CardColumn {
      Text("User Location clicked $locationClickedCount times")
      Text("${state.locationState.location}")
    }
  }
}
