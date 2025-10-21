package org.maplibre.compose.demoapp.demos

import android.annotation.SuppressLint
import androidx.compose.material3.Button
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
import org.maplibre.compose.gms.rememberFusedLocationProvider
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.rememberUserLocationState
import org.maplibre.compose.material3.LocationPuckDefaults

object GmsLocationDemo : Demo {
  override val name = "Gms Location"

  private var locationClickedCount by mutableIntStateOf(0)

  @Composable
  override fun MapContent(state: DemoState, isOpen: Boolean) {
    if (!isOpen) return

    val coroutineScope = rememberCoroutineScope()

    // this if _is_ a permission check Lint just doesn't know that
    @SuppressLint("MissingPermission")
    if (state.locationPermissionState.hasPermission) {
      val locationProvider = rememberFusedLocationProvider()
      val locationState = rememberUserLocationState(locationProvider)

      LocationPuck(
        idPrefix = "gms-location",
        locationState = locationState,
        cameraState = state.cameraState,
        accuracyThreshold = 0f,
        colors = LocationPuckDefaults.colors(),
        onClick = { location ->
          locationClickedCount++
          coroutineScope.launch {
            state.cameraState.animateTo(CameraPosition(target = location.position, zoom = 16.0))
          }
        },
      )
    }
  }

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    if (!state.locationPermissionState.hasPermission) {
      Button(onClick = state.locationPermissionState::requestPermission) {
        Text("Request permission")
      }
    } else {
      CardColumn { Text("User Location clicked $locationClickedCount times") }
    }
  }
}
