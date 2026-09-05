@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.getBaseSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.toJson

@Composable
@OptIn(ExperimentalResourceApi::class)
fun Layers() {
  // #region simple
  val baseState =
    rememberMapState(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")) {
      getBaseSource(id = "openmaptiles")?.let { tiles ->
        CircleLayer(id = "example", source = tiles, sourceLayer = "poi")
      }
    }
  MaplibreMap(state = baseState)
  // #endregion simple

  val amtrakState = rememberMapState {
    // #region amtrak-1
    val amtrakStations =
      rememberGeoJsonSource(GeoJsonData.Uri(Res.getUri("files/data/amtrak_stations.geojson")))

    val amtrakRoutes =
      rememberGeoJsonSource(GeoJsonData.Uri(Res.getUri("files/data/amtrak_routes.geojson")))
    LineLayer(
      id = "amtrak-routes-casing",
      source = amtrakRoutes,
      color = const(Color.White),
      width = const(6.dp),
    )
    LineLayer(
      id = "amtrak-routes",
      source = amtrakRoutes,
      color = const(Color.Blue),
      width = const(4.dp),
    )
    // #endregion amtrak-1

    // #region amtrak-2
    val detailedAmtrakRoutes =
      rememberGeoJsonSource(GeoJsonData.Uri(Res.getUri("files/data/amtrak_routes.geojson")))
    LineLayer(
      id = "amtrak-routes",
      source = detailedAmtrakRoutes,
      cap = const(LineCap.Round),
      join = const(LineJoin.Round),
      color = const(Color.Blue),
      width =
        interpolate(
          type = exponential(1.2f),
          input = zoom(),
          5 to const(0.4.dp),
          6 to const(0.7.dp),
          7 to const(1.75.dp),
          20 to const(22.dp),
        ),
    )
    // #endregion amtrak-2

    // #region transitions
    val timedAmtrakStations =
      rememberGeoJsonSource(GeoJsonData.Uri(Res.getUri("files/data/amtrak_stations.geojson")))
    CircleLayer(
      id = "amtrak-stations-timed",
      source = timedAmtrakStations,
      color = const(if (isSystemInDarkTheme()) Color.Cyan else Color.Blue),
      colorTransition = TransitionOptions(duration = 500.milliseconds),
    )
    // #endregion transitions

    // #region anchors
    val anchoredAmtrakRoutes =
      rememberGeoJsonSource(GeoJsonData.Uri(Res.getUri("files/data/amtrak_routes.geojson")))
    Anchor.Above("road_motorway") {
      LineLayer(id = "amtrak-routes", source = anchoredAmtrakRoutes)
    }
    // #endregion anchors

    // #region interaction
    val interactiveAmtrakStations =
      rememberGeoJsonSource(GeoJsonData.Uri(Res.getUri("files/data/amtrak_stations.geojson")))
    CircleLayer(
      id = "amtrak-stations",
      source = interactiveAmtrakStations,
      hitPadding = 12.dp,
      onClick = { features ->
        println("Clicked on ${features[0].toJson()}")
        ClickResult.Consume
      },
    )
    // #endregion interaction
  }
  MaplibreMap(state = amtrakState)
}
