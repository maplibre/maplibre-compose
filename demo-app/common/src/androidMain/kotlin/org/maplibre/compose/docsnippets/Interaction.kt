@file:Suppress("unused", "UNUSED_ANONYMOUS_PARAMETER")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.toJson

@Composable
fun Interaction() {
  // -8<- [start:common-gesture-ornament]
  MaplibreMap(
    options =
      MapOptions(
        gestureOptions = GestureOptions.Standard,
        ornamentOptions = OrnamentOptions.OnlyLogo,
      )
  )
  // -8<- [end:common-gesture-ornament]

  // -8<- [start:gesture-settings]
  MaplibreMap(
    options =
      MapOptions(
        gestureOptions =
          GestureOptions(
            isTiltEnabled = true,
            isZoomEnabled = true,
            isRotateEnabled = true,
            isScrollEnabled = true,
          )
      )
  )
  // -8<- [end:gesture-settings]

  // -8<- [start:ornament-settings]
  MaplibreMap(
    options =
      MapOptions(
        ornamentOptions =
          OrnamentOptions(
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
        println("Clicked on ${features[0].toJson()}")
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
