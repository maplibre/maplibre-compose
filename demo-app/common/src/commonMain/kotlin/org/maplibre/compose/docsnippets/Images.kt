@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.map_24px
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.ResolvedStyleImage
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.sources.rememberImageSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.PositionQuad
import org.maplibre.spatialk.geojson.Position

@Composable
fun Images() {
  val iconState = rememberMapState {
    // #region icon-painter
    val earthquakes =
      rememberGeoJsonSource(
        GeoJsonData.Uri("https://maplibre.org/maplibre-gl-js/docs/assets/earthquakes.geojson")
      )

    SymbolLayer(
      id = "earthquake-icons",
      source = earthquakes,
      iconImage = image(painterResource(Res.drawable.map_24px), size = DpSize(24.dp, 24.dp)),
    )
    // #endregion icon-painter
  }
  MaplibreMap(state = iconState)

  // #region icon-sprite
  val spriteState =
    rememberMapState(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")) {
      val earthquakes =
        rememberGeoJsonSource(
          GeoJsonData.Uri("https://maplibre.org/maplibre-gl-js/docs/assets/earthquakes.geojson")
        )

      SymbolLayer(id = "earthquake-markers", source = earthquakes, iconImage = image("marker"))
    }
  MaplibreMap(state = spriteState)
  // #endregion icon-sprite

  val imageState = rememberMapState {
    // #region image-source
    val corners =
      PositionQuad(
        topLeft = Position(longitude = -74.0178, latitude = 40.7040),
        topRight = Position(longitude = -74.0118, latitude = 40.7100),
        bottomRight = Position(longitude = -74.0060, latitude = 40.7067),
        bottomLeft = Position(longitude = -74.0120, latitude = 40.7006),
      )
    val plan = rememberImageSource(position = corners, uri = Res.getUri("files/castello-plan.jpg"))
    RasterLayer(id = "castello-plan", source = plan, opacity = const(0.8f))
    // #endregion image-source
  }
  MaplibreMap(state = imageState)
}

@Composable
fun MissingImages(fallback: Painter) {
  // #region missing-image
  val mapState = rememberMapState()
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  DisposableEffect(mapState, fallback, density, layoutDirection) {
    mapState.missingImageResolver = {
      ResolvedStyleImage.fromPainter(fallback, density, layoutDirection)
    }
    onDispose { mapState.missingImageResolver = null }
  }
  MaplibreMap(state = mapState)
  // #endregion missing-image
}
