@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.VectorTileSourceHandle
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

// #region query-rendered
@Composable
fun NearbyPlaces(onPlacesFound: (List<String>) -> Unit) {
  val state =
    rememberMapState(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"))
  val scope = rememberCoroutineScope()
  MaplibreMap(
    state = state,
    interactions =
      MapInteractions {
        callbacks {
          longClick {
            onEvent { event ->
              val area =
                DpRect(
                  origin = event.screenOffset - DpOffset(24.dp, 24.dp),
                  size = DpSize(48.dp, 48.dp),
                )
              scope.launch {
                val places =
                  state.queryRenderedFeatures(
                    rect = area,
                    layerIds = setOf("poi_r1", "poi_r7", "poi_r20"),
                  )
                onPlacesFound(
                  places.mapNotNull { it.properties?.get("name")?.jsonPrimitive?.contentOrNull }
                )
              }
              ClickResult.Consume
            }
          }
        }
      },
  )
}

// #endregion query-rendered

// #region query-source
suspend fun loadedCafes(state: MapState): List<Feature<Geometry, JsonObject?>> {
  val tiles = state.style.sources["openmaptiles"] as? VectorTileSourceHandle ?: return emptyList()
  return tiles.querySourceFeatures(
    sourceLayerIds = setOf("poi"),
    predicate = feature["class"] eq const("cafe"),
  )
}

// #endregion query-source

// #region highlight-selected
@Composable
fun SelectableStops() {
  val stops = remember {
    buildFeatureCollection<Point, JsonObject?> {
      // Feature state needs an integer id on each feature.
      addFeature(geometry = Point(Position(longitude = -122.4194, latitude = 37.7793))) {
        setId(1)
      }
      addFeature(geometry = Point(Position(longitude = -122.4089, latitude = 37.7841))) {
        setId(2)
      }
      addFeature(geometry = Point(Position(longitude = -122.3937, latitude = 37.7955))) {
        setId(3)
      }
    }
  }
  val state =
    rememberMapState(
      baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
      initialCameraPosition =
        CameraPosition(center = Position(longitude = -122.41, latitude = 37.785), zoom = 13.0),
    ) {
      val mapState = LocalMapState.current
      val source = rememberGeoJsonSource(GeoJsonData.Features(stops))
      var selectedId by remember { mutableStateOf<String?>(null) }
      CircleLayer(
        id = "stops",
        source = source,
        radius = const(10.dp),
        color =
          switch(
            condition(feature.state("selected").asBoolean(const(false)), const(Color.Red)),
            fallback = const(Color.Blue),
          ),
        onClick = { features ->
          val handle = mapState?.style?.sources?.get(source)
          val id = features.first().id?.content
          if (handle != null && id != null) {
            selectedId?.let { handle.removeFeatureState(it, "selected") }
            handle.setFeatureState(id, buildJsonObject { put("selected", true) })
            selectedId = id
          }
          ClickResult.Consume
        },
      )
    }
  MaplibreMap(state = state)
}
// #endregion highlight-selected
