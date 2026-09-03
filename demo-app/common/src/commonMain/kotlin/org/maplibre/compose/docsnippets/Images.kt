@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.map_24px
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.sources.rememberImageSource
import org.maplibre.compose.util.PositionQuad
import org.maplibre.spatialk.geojson.Position

@Composable
@OptIn(ExperimentalResourceApi::class)
fun Images() {
  val iconState = rememberMapState {
    // #region icon-painter
    val stations =
      rememberGeoJsonSource(GeoJsonData.Uri(Res.getUri("files/data/amtrak_stations.geojson")))

    SymbolLayer(
      id = "station-icons",
      source = stations,
      iconImage = image(painterResource(Res.drawable.map_24px), size = DpSize(24.dp, 24.dp)),
    )
    // #endregion icon-painter

    // #region icon-sprite
    SymbolLayer(id = "station-markers", source = stations, iconImage = image("marker"))
    // #endregion icon-sprite
  }
  MaplibreMap(state = iconState)

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
fun MissingImages(fallback: ImageBitmap) {
  // #region missing-image
  val mapState = rememberMapState()
  LaunchedEffect(mapState) {
    val supplied = mutableSetOf<String>()
    mapState.events.collect { event ->
      when (event) {
        // A loaded style keeps none of the images added to the style before it.
        MapEvent.StyleLoaded -> supplied.clear()
        is MapEvent.StyleImageMissing ->
          if (supplied.add(event.imageId)) {
            snapshotFlow { mapState.style.loadState }.first { it == StyleLoadState.Ready }
            mapState.style.images.add(event.imageId, fallback)
          }
        else -> Unit
      }
    }
  }
  MaplibreMap(state = mapState)
  // #endregion missing-image
}
