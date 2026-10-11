@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.elevation
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.value.IlluminationAnchor
import org.maplibre.compose.expressions.value.ProjectionType
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.ColorReliefLayer
import org.maplibre.compose.layers.FillExtrusionLayer
import org.maplibre.compose.layers.HillshadeLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.VectorTileSource
import org.maplibre.compose.sources.getBaseSource
import org.maplibre.compose.sources.rememberRasterDemTileSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.Light
import org.maplibre.compose.style.Projection
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi
import org.maplibre.spatialk.geojson.Position

@Composable
fun Terrain() {
  // #region buildings
  val mapState =
    rememberMapState(
      baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/bright"),
      initialCameraPosition =
        CameraPosition(
          center = Position(latitude = 40.7135, longitude = -74.0066),
          zoom = 15.5,
          pitch = 45.0,
          bearing = -17.6,
        ),
    ) {
      getBaseSource<VectorTileSource>(id = "openmaptiles")?.let { tiles ->
        // Below the base style's labels
        Anchor.Below({ it.type == "symbol" }) {
          FillExtrusionLayer(
            id = "3d-buildings",
            source = tiles,
            sourceLayer = "building",
            minZoom = 15f,
            color = const(Color.LightGray),
            height = feature["render_height"].asNumber(const(0f)),
            base = feature["render_min_height"].asNumber(const(0f)),
          )
        }
      }
    }
  MaplibreMap(state = mapState)
  // #endregion buildings

  // #region light
  LaunchedEffect(mapState, mapState.style.loadState) {
    if (mapState.style.loadState == StyleLoadState.Ready) {
      mapState.style.light.set(
        Light(
          // Light from the west, whichever way the map is rotated
          anchor = const(IlluminationAnchor.Map),
          position = const(listOf(1.5f, 270f, 60f)),
          color = const(Color(0xFFFFE0B2)),
          intensity = const(0.6f),
        )
      )
    }
  }
  // #endregion light
}

@Composable
fun HillshadeMap() {
  // #region hillshade
  val mapState =
    rememberMapState(
      baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/positron"),
      initialCameraPosition =
        CameraPosition(center = Position(latitude = 47.27, longitude = 11.39), zoom = 10.0),
    ) {
      val dem =
        rememberRasterDemTileSource(
          uri = "https://demotiles.maplibre.org/terrain-tiles/tiles.json",
          tileSize = 256,
        )
      Anchor.Below({ it.type == "symbol" }) {
        HillshadeLayer(id = "hillshade", source = dem, exaggeration = const(0.7f))
      }
    }
  MaplibreMap(state = mapState)
  // #endregion hillshade
}

@Composable
fun ColorReliefMap() {
  val state = rememberMapState {
    // #region color-relief
    val dem =
      rememberRasterDemTileSource(
        uri = "https://demotiles.maplibre.org/terrain-tiles/tiles.json",
        tileSize = 256,
      )
    ColorReliefLayer(
      id = "color-relief",
      source = dem,
      color =
        interpolate(
          type = linear(),
          input = elevation(),
          500 to const(Color(0xFF4CAF50)),
          1500 to const(Color(0xFFF0E68C)),
          2500 to const(Color(0xFF8B5A2B)),
          3000 to const(Color.White),
        ),
      opacity = const(0.6f),
    )
    // #endregion color-relief
  }
  MaplibreMap(state = state)
}

// #region globe
@OptIn(ExperimentalMaplibreComposeApi::class)
@Composable
fun GlobeMap() {
  val mapState = rememberMapState(initialCameraPosition = CameraPosition(zoom = 1.5))
  LaunchedEffect(mapState, mapState.style.loadState) {
    if (mapState.style.loadState == StyleLoadState.Ready) {
      mapState.style.projection.set(Projection(type = const(ProjectionType.Globe)))
    }
  }
  MaplibreMap(state = mapState)
}
// #endregion globe
