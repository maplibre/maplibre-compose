@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.ProjectionType
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.RasterDemEncoding
import org.maplibre.compose.sources.RasterDemTileSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.Projection
import org.maplibre.compose.style.Sky
import org.maplibre.compose.style.StyleOverrides
import org.maplibre.compose.style.WebTerrain
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

@OptIn(ExperimentalMaplibreComposeApi::class)
@Composable
fun BrowserStyleOverrides() {
  // #region terrain
  val dem = remember {
    RasterDemTileSource(
      id = "terrain-dem",
      tiles = listOf("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"),
      tileSize = 256,
      encoding = RasterDemEncoding.Terrarium,
    )
  }
  val overrides = StyleOverrides {
    sky = Sky()
    projection = Projection(type = const(ProjectionType.Globe))
    terrain = WebTerrain(dem, exaggeration = 1.5)
  }
  val state =
    rememberMapState(
      baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
      styleOverrides = overrides,
    )
  MaplibreMap(state = state)
  // #endregion terrain
}
