@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.getBaseSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.toJson

@Composable
@OptIn(ExperimentalResourceApi::class)
fun Layers() {
  // -8<- [start:simple]
  MaplibreMap(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")) {
    getBaseSource(id = "openmaptiles")?.let { tiles ->
      CircleLayer(id = "example", source = tiles, sourceLayer = "poi")
    }
  }
  // -8<- [end:simple]

  MaplibreMap {
    val amtrakStations =
      rememberGeoJsonSource(GeoJsonData.Uri(Res.getUri("files/data/amtrak_stations.geojson")))

    // -8<- [start:amtrak-1]
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
    // -8<- [end:amtrak-1]

    // -8<- [start:amtrak-2]
    LineLayer(
      id = "amtrak-routes",
      source = amtrakRoutes,
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
    // -8<- [end:amtrak-2]

    // -8<- [start:anchors]
    Anchor.Above("road_motorway") { LineLayer(id = "amtrak-routes", source = amtrakRoutes) }
    // -8<- [end:anchors]

    // -8<- [start:interaction]
    CircleLayer(
      id = "amtrak-stations",
      source = amtrakStations,
      onClick = { features ->
        println("Clicked on ${features[0].toJson()}")
        ClickResult.Consume
      },
    )
    // -8<- [end:interaction]
  }
}
