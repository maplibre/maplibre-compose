@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
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
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.PreparedImage
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
fun ImageSourceFrames(corners: PositionQuad, frames: List<ImageBitmap>) {
  // #region image-source-frames
  val prepared by
    produceState(emptyList<PreparedImage>(), frames) {
      value = withContext(Dispatchers.Default) { frames.map(PreparedImage::fromBitmap) }
    }
  var frame by remember { mutableIntStateOf(0) }
  LaunchedEffect(prepared) {
    while (prepared.isNotEmpty()) {
      delay(100.milliseconds)
      frame = (frame + 1) % prepared.size
    }
  }
  val mapState = rememberMapState {
    if (prepared.isNotEmpty()) {
      val image = prepared[frame % prepared.size]
      RasterLayer(id = "radar", source = rememberImageSource(position = corners, image = image))
    }
  }
  MaplibreMap(state = mapState)
  // #endregion image-source-frames
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
