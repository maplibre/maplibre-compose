package org.maplibre.compose.demoapp.demos

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.material3.LocationLayer

object UserLocationDemo : Demo {
  override val name = "User Location"

  private var locationClickedCount by mutableIntStateOf(0)

  @Composable
  override fun MapContent(state: DemoState, isOpen: Boolean) {
    if (!isOpen) return

    val coroutineScope = rememberCoroutineScope()

    LocationLayer(
      id = "user-location",
      locationState = state.locationState,
      cameraState = state.cameraState,
      accuracyThreshold = 0f,
      onClick = { location ->
        locationClickedCount++
        coroutineScope.launch {
          state.cameraState.animateTo(CameraPosition(target = location.position, zoom = 16.0))
        }
      },
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
