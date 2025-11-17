package org.maplibre.compose.demoapp.demos

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToNumber
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.plus
import org.maplibre.compose.expressions.dsl.step
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf
import org.maplibre.spatialk.geojson.toJson

object ClusteredPointsDemo : Demo {
  override val name = "Clustered points"

  override val region =
    BoundingBox(southwest = Position(-122.4594, 47.4951), northeast = Position(-122.2244, 47.7341))

  override val mapContentVisibilityState = mutableStateOf(true)

  private val LimeGreen = Color(50, 205, 5)

  @Composable
  override fun MapContent(state: DemoState, isOpen: Boolean) {
    val coroutineScope = rememberCoroutineScope()

    // TODO isLoading as some standard state on the Demo, exposed to the main UI?
    //    var isLoading by remember { mutableStateOf(true) }
    var data by remember { mutableStateOf(featureCollectionOf().toJson()) }
    LaunchedEffect(Unit) {
      withContext(Dispatchers.Default) {
        try {
          data = getLimeBikeStatusAsGeoJson()
          //        isLoading = false
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }
    }

    val bikeSource =
      rememberGeoJsonSource(
        GeoJsonData.JsonString(data),
        GeoJsonOptions(
          minZoom = 8,
          cluster = true,
          clusterRadius = 32,
          clusterMaxZoom = 16,
          clusterProperties =
            mapOf(
              "total_range" to
                GeoJsonOptions.ClusterPropertyAggregator(
                  mapper = feature["current_range_meters"].convertToNumber(),
                  reducer =
                    feature.accumulated().asNumber() + feature["total_range"].convertToNumber(),
                )
            ),
        ),
      )

    CircleLayer(
      id = "clustered-bikes",
      source = bikeSource,
      filter = feature.has("point_count"),
      color = const(LimeGreen),
      opacity = const(0.5f),
      radius =
        step(
          input = feature["point_count"].asNumber(),
          fallback = const(15.dp),
          25 to const(20.dp),
          100 to const(30.dp),
          500 to const(40.dp),
          1000 to const(50.dp),
          5000 to const(60.dp),
        ),
      onClick = { features ->
        features
          .firstOrNull { bikeSource.isCluster(it) }
          ?.let {
            coroutineScope.launch {
              state.cameraState.animateTo(
                state.cameraState.position.copy(
                  target = (it.geometry as Point).coordinates,
                  zoom = bikeSource.getClusterExpansionZoom(it),
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
      textField = feature["point_count_abbreviated"].asString(),
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
      id = "unclustered-bikes-circle",
      source = bikeSource,
      filter = !feature.has("point_count"),
      color = const(Color.White),
      radius = const(7.dp),
      strokeWidth = const(3.dp),
      strokeColor = const(Color.White),
    )

    SymbolLayer(
      id = "unclustered-bikes-icons",
      source = bikeSource,
      filter = !feature.has("point_count"),
      iconImage =
        image(
          value = rememberVectorPainter(Icons.Default.PedalBike),
          size = DpSize(14.dp, 14.dp),
          alpha = 0.5f,
          colorFilter = ColorFilter.tint(LimeGreen),
        ),
      iconAllowOverlap = const(true),
    )
  }

  private suspend fun getLimeBikeStatusAsGeoJson(): String {
    val bodyString =
      HttpClient()
        .get("https://data.lime.bike/api/partners/v2/gbfs/seattle/free_bike_status.json")
        .bodyAsText()
    val body = Json.parseToJsonElement(bodyString).jsonObject
    val bikes = body["data"]!!.jsonObject["bikes"]!!.jsonArray.map { it.jsonObject }
    val features =
      bikes.map { bike ->
        Feature(
          id = bike["bike_id"]!!.jsonPrimitive,
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
    return FeatureCollection(features).toJson()
  }
}
