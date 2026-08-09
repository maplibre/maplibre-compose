@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.material3.CompassButton
import org.maplibre.compose.material3.DisappearingCompassButton
import org.maplibre.compose.material3.DisappearingScaleBar
import org.maplibre.compose.material3.ExpandingAttributionButton
import org.maplibre.compose.material3.ScaleBar
import org.maplibre.compose.style.rememberStyleState

@Composable
fun Material3() {
  // -8<- [start:controls]
  val cameraState = rememberCameraState()
  val styleState = rememberStyleState()

  Box(Modifier.fillMaxSize()) {
    MaplibreMap(
      cameraState = cameraState,
      styleState = styleState,
      options = MapOptions(ornamentOptions = OrnamentOptions.OnlyLogo),
    )

    Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
      ScaleBar(cameraState.metersPerDpAtTarget, modifier = Modifier.align(Alignment.TopStart))
      CompassButton(cameraState, modifier = Modifier.align(Alignment.TopEnd))
      ExpandingAttributionButton(
        cameraState = cameraState,
        styleState = styleState,
        modifier = Modifier.align(Alignment.BottomEnd),
        contentAlignment = Alignment.BottomEnd,
      )
    }
  }
  // -8<- [end:controls]

  // -8<- [start:disappearing-controls]
  Box(modifier = Modifier.fillMaxSize()) {
    MaplibreMap(
      cameraState = cameraState,
      styleState = styleState,
      options = MapOptions(ornamentOptions = OrnamentOptions.OnlyLogo),
    )

    Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
      DisappearingScaleBar(
        metersPerDp = cameraState.metersPerDpAtTarget,
        zoom = cameraState.position.zoom,
        modifier = Modifier.align(Alignment.TopStart),
      ) // (1)!
      DisappearingCompassButton(cameraState, modifier = Modifier.align(Alignment.TopEnd)) // (2)!
      ExpandingAttributionButton(
        cameraState = cameraState,
        styleState = styleState,
        modifier = Modifier.align(Alignment.BottomEnd),
        contentAlignment = Alignment.BottomEnd,
      )
    }
  }
  // -8<- [end:disappearing-controls]
}
