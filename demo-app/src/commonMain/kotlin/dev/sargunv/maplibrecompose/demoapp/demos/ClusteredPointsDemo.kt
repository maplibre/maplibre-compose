package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.sargunv.maplibrecompose.compose.ClickResult
import dev.sargunv.maplibrecompose.compose.MaplibreMap
import dev.sargunv.maplibrecompose.compose.layer.CircleLayer
import dev.sargunv.maplibrecompose.compose.layer.SymbolLayer
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.compose.rememberStyleState
import dev.sargunv.maplibrecompose.compose.source.rememberGeoJsonSource
import dev.sargunv.maplibrecompose.core.CameraPosition
import dev.sargunv.maplibrecompose.core.source.GeoJsonData
import dev.sargunv.maplibrecompose.core.source.GeoJsonOptions
import dev.sargunv.maplibrecompose.demoapp.DEFAULT_STYLE
import dev.sargunv.maplibrecompose.demoapp.Demo
import dev.sargunv.maplibrecompose.demoapp.DemoMapControls
import dev.sargunv.maplibrecompose.demoapp.DemoOrnamentSettings
import dev.sargunv.maplibrecompose.demoapp.DemoScaffold
import dev.sargunv.maplibrecompose.demoapp.generated.Res
import dev.sargunv.maplibrecompose.expressions.dsl.asNumber
import dev.sargunv.maplibrecompose.expressions.dsl.asString
import dev.sargunv.maplibrecompose.expressions.dsl.const
import dev.sargunv.maplibrecompose.expressions.dsl.feature
import dev.sargunv.maplibrecompose.expressions.dsl.not
import dev.sargunv.maplibrecompose.expressions.dsl.offset
import dev.sargunv.maplibrecompose.expressions.dsl.step
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.FeatureCollection
import io.github.dellisd.spatialk.geojson.Point
import io.github.dellisd.spatialk.geojson.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.ExperimentalResourceApi

private const val GBFS_FILE = "files/data/lime_seattle.gbfs.json"

private val SEATTLE = Position(latitude = 47.607, longitude = -122.342)

private val LIME_GREEN = Color(50, 205, 5)

object ClusteredPointsDemo : Demo {
  override val name = "Clustering and interaction"
  override val description = "Add points to the map and configure clustering with expressions."

  @Composable
  override fun Component(navigateUp: () -> Unit) {
    DemoScaffold(this, navigateUp) {
      val cameraState =
        rememberCameraState(firstPosition = CameraPosition(target = SEATTLE, zoom = 10.0))
      val styleState = rememberStyleState()
      val isLoading = remember { mutableStateOf(true) }

      val coroutineScope = rememberCoroutineScope()

      Box(modifier = Modifier.fillMaxSize()) {
        MaplibreMap(
          styleUri = DEFAULT_STYLE,
          cameraState = cameraState,
          styleState = styleState,
          ornamentSettings = DemoOrnamentSettings(),
        ) {
          val gbfsData by rememberGbfsFeatureState(GBFS_FILE, isLoading)

          val bikeSource =
            rememberGeoJsonSource(
              "bikes",
              GeoJsonData.JsonString(gbfsData),
              GeoJsonOptions(
                cluster = true,
                clusterRadius = 32,
                clusterMaxZoom = 16,
                // TODO on Android, this segfaults when the mapper is anything but a constant
                // See https://github.com/maplibre/maplibre-native/issues/3493
                // clusterProperties =
                //   mapOf(
                //     "total_range" to
                //       GeoJsonOptions.ClusterPropertyAggregator(
                //         mapper = feature.get("current_range_meters").asNumber(),
                //         reducer =
                //           feature.accumulated().asNumber() +
                //             feature.get("total_range").asNumber(),
                //       )
                //   ),
              ),
            )

          CircleLayer(
            id = "clustered-bikes",
            source = bikeSource,
            filter = feature.has("point_count"),
            color = const(LIME_GREEN),
            opacity = const(0.5f),
            radius =
              step(
                input = feature.get("point_count").asNumber(),
                fallback = const(15.dp),
                25 to const(20.dp),
                100 to const(30.dp),
                500 to const(40.dp),
                1000 to const(50.dp),
                5000 to const(60.dp),
              ),
            onClick = { features ->
              features.firstOrNull()?.geometry?.let {
                coroutineScope.launch {
                  cameraState.animateTo(
                    cameraState.position.copy(
                      target = (it as Point).coordinates,
                      zoom = (cameraState.position.zoom + 2).coerceAtMost(20.0),
                    )
                  )
                }
                ClickResult.Consume
              } ?: ClickResult.Pass
            },
          )

          SymbolLayer(
            id = "clustered-bikes-count",
            source = bikeSource,
            filter = feature.has("point_count"),
            textField = feature.get("point_count_abbreviated").asString(),
            textFont = const(listOf("Noto Sans Regular")),
            textColor = const(MaterialTheme.colorScheme.onBackground),
          )

          CircleLayer(
            id = "unclustered-bikes-shadow",
            source = bikeSource,
            filter = !feature.has("point_count"),
            radius = const(13.dp),
            color = const(Color.Black),
            blur = const(1f),
            translate = offset(0.dp, 1.dp),
          )

          CircleLayer(
            id = "unclustered-bikes",
            source = bikeSource,
            filter = !feature.has("point_count"),
            color = const(LIME_GREEN),
            radius = const(7.dp),
            strokeWidth = const(3.dp),
            strokeColor = const(Color.White),
          )
        }
        DemoMapControls(cameraState, styleState)
        if (isLoading.value) {
          CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
      }
    }
  }
}

@Composable
private fun rememberGbfsFeatureState(
  gbfsFilePath: String,
  isLoading: MutableState<Boolean>,
): State<String> {
  val dataState = remember { mutableStateOf(FeatureCollection().json()) }
  LaunchedEffect(gbfsFilePath) {
    withContext(Dispatchers.Default) {
      isLoading.value = true
      val response = readGbfsData(gbfsFilePath)
      dataState.value = response
      isLoading.value = false
    }
  }
  return dataState
}

@OptIn(ExperimentalResourceApi::class)
private suspend fun readGbfsData(gbfsFilePath: String): String {
  val bodyString = Res.readBytes(gbfsFilePath).decodeToString()
  val body = Json.parseToJsonElement(bodyString).jsonObject
  val bikes = body["data"]!!.jsonObject["bikes"]!!.jsonArray.map { it.jsonObject }
  val features =
    bikes.map { bike ->
      Feature(
        id = bike["bike_id"]!!.jsonPrimitive.content,
        geometry =
          Point(
            Position(
              longitude = bike["lon"]!!.jsonPrimitive.double,
              latitude = bike["lat"]!!.jsonPrimitive.double,
            )
          ),
        properties =
          mapOf(
            "vehicle_type" to (bike["vehicle_type"] ?: JsonNull),
            "vehicle_type_id" to (bike["vehicle_type_id"] ?: JsonNull),
            "last_reported" to (bike["last_reported"] ?: JsonNull),
            "current_range_meters" to (bike["current_range_meters"] ?: JsonPrimitive(0)),
            "is_reserved" to (bike["is_reserved"] ?: JsonNull),
            "is_disabled" to (bike["is_disabled"] ?: JsonNull),
          ),
      )
    }
  return FeatureCollection(features).json()
}
