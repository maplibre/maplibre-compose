@file:Suppress("unused", "UNUSED_ANONYMOUS_PARAMETER")

package org.maplibre.maplibrecompose.demoapp.docs

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import io.github.dellisd.spatialk.geojson.Position
import org.maplibre.maplibrecompose.compose.ClickResult
import org.maplibre.maplibrecompose.compose.MaplibreMap
import org.maplibre.maplibrecompose.compose.rememberCameraState
import org.maplibre.maplibrecompose.core.CameraPosition
import org.maplibre.maplibrecompose.core.GestureSettings
import org.maplibre.maplibrecompose.core.OrnamentSettings
import kotlin.time.Duration.Companion.seconds

@Composable
fun Interaction() {
  // -8<- [start:gesture-settings]
  MaplibreMap(
    gestureSettings =
      GestureSettings(
        isTiltGesturesEnabled = true,
        isZoomGesturesEnabled = true, // (1)!
        isRotateGesturesEnabled = true,
        isScrollGesturesEnabled = true,
      )
  )
  // -8<- [end:gesture-settings]

  // -8<- [start:ornament-settings]
  MaplibreMap(
    ornamentSettings =
      OrnamentSettings(
        padding = PaddingValues(0.dp), // (1)!
        isLogoEnabled = true, // (2)!
        logoAlignment = Alignment.BottomStart, // (3)!
        isAttributionEnabled = true, // (4)!
        attributionAlignment = Alignment.BottomEnd,
        isCompassEnabled = true, // (5)!
        compassAlignment = Alignment.TopEnd,
        isScaleBarEnabled = true, // (6)!
        scaleBarAlignment = Alignment.TopStart,
      )
  )
  // -8<- [end:ornament-settings]

  // -8<- [start:camera]
  val camera =
    rememberCameraState(
      firstPosition =
        CameraPosition(target = Position(latitude = 45.521, longitude = -122.675), zoom = 13.0)
    )
  MaplibreMap(cameraState = camera)
  // -8<- [end:camera]

  // -8<- [start:camera-animate]
  LaunchedEffect(Unit) {
    camera.animateTo(
      finalPosition =
        camera.position.copy(target = Position(latitude = 47.607, longitude = -122.342)),
      duration = 3.seconds,
    )
  }
  // -8<- [end:camera-animate]

  // -8<- [start:click-listeners]
  MaplibreMap(
    cameraState = camera,
    onMapClick = { pos, offset ->
      val features = camera.projection?.queryRenderedFeatures(offset)
      if (!features.isNullOrEmpty()) {
        println("Clicked on ${features[0].json()}")
        ClickResult.Consume // (1)!
      } else {
        ClickResult.Pass
      }
    },
    onMapLongClick = { pos, offset ->
      println("Long click at $pos")
      ClickResult.Pass
    },
  )
  // -8<- [end:click-listeners]
}
