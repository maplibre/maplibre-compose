package org.maplibre.compose.snippetdemos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/** Demonstrates the camera guide: animate to a position and fit a bounding box. */
@Composable
fun CameraSnippetDemo() {
  val camera =
    rememberCameraState(
      firstPosition =
        CameraPosition(target = Position(latitude = 45.521, longitude = -122.675), zoom = 10.0)
    )
  val scope = rememberCoroutineScope()

  MaplibreMap(
    baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
    cameraState = camera,
    overlay =
      MapOverlay {
        include(MapOverlay.Default)
        Row(
          modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
            onClick = {
              scope.launch {
                camera.animateTo(
                  finalPosition =
                    CameraPosition(
                      target = Position(latitude = 47.607, longitude = -122.342),
                      zoom = 11.0,
                    ),
                  duration = 3.seconds,
                )
              }
            }
          ) {
            Text("Animate to Seattle")
          }
          Button(
            onClick = {
              scope.launch {
                camera.animateTo(
                  boundingBox =
                    BoundingBox(west = -124.8, south = 45.0, east = -116.9, north = 49.0),
                  duration = 3.seconds,
                )
              }
            }
          ) {
            Text("Fit the Northwest")
          }
        }
      },
  )
}
