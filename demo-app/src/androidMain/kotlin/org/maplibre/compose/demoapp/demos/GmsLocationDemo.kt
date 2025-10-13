package org.maplibre.compose.demoapp.demos

import android.annotation.SuppressLint
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
import org.maplibre.compose.gms.rememberFusedGeoLocator
import org.maplibre.compose.location.rememberUserLocationState
import org.maplibre.compose.material3.LocationLayer

object GmsLocationDemo : Demo {
  override val name = "Gms Location"

  private var locationClickedCount by mutableIntStateOf(0)

  @Composable
  override fun MapContent(state: DemoState, isOpen: Boolean) {
    if (!isOpen) return

    val coroutineScope = rememberCoroutineScope()
    @SuppressLint("MissingPermission") val geoLocator = rememberFusedGeoLocator()
    val locationState = rememberUserLocationState(geoLocator)

    LocationLayer(
      id = "gms-location",
      locationState = locationState,
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
    CardColumn { Text("User Location clicked $locationClickedCount times") }
  }
}
