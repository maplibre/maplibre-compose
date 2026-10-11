@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.RasterDemTileSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleOverrides
import org.maplibre.compose.style.WebTerrain
import org.maplibre.spatialk.geojson.Position

@Composable
fun BrowserTerrainMap() {
  // #region terrain
  val dem = remember {
    RasterDemTileSource(
      id = "terrain-dem",
      uri = "https://demotiles.maplibre.org/terrain-tiles/tiles.json",
      tileSize = 256,
    )
  }
  val mapState =
    rememberMapState(
      baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/positron"),
      styleOverrides = StyleOverrides { terrain = WebTerrain(dem, exaggeration = 1.5) },
      initialCameraPosition =
        CameraPosition(
          center = Position(latitude = 47.27, longitude = 11.39),
          zoom = 11.0,
          pitch = 60.0,
        ),
    )
  MaplibreMap(state = mapState)
  // #endregion terrain
}
