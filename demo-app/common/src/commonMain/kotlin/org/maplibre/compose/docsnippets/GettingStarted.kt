@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

// #region first-map
@Composable
fun MyMap() {
  val mapState =
    rememberMapState(
      baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
      initialCameraPosition =
        CameraPosition(center = Position(latitude = 45.521, longitude = -122.675), zoom = 13.0),
    )
  MaplibreMap(state = mapState)
}
// #endregion first-map
