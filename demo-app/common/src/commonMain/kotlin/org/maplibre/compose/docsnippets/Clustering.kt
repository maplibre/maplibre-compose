@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToString
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.step
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Point

private const val EarthquakesUri =
  "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_month.geojson"

@Composable
fun Clustering() {
  val state =
    rememberMapState(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")) {
      // #region source
      val earthquakes =
        rememberGeoJsonSource(
          GeoJsonData.Uri(
            "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_month.geojson"
          )
        ) {
          cluster = true
          clusterRadius = 40
          clusterMaxZoom = 10
        }
      // #endregion source

      // #region layers
      val pointCount = feature["point_count"].asNumber()

      // Clusters: bigger and warmer as they hold more points
      CircleLayer(
        id = "earthquake-clusters",
        source = earthquakes,
        filter = feature.has("point_count"),
        radius = step(pointCount, const(14.dp), 25 to const(20.dp), 100 to const(28.dp)),
        color =
          step(
            pointCount,
            const(Color(0xFF4FC3F7)),
            25 to const(Color(0xFFFFB74D)),
            100 to const(Color(0xFFE57373)),
          ),
      )

      // The number of points on each cluster
      SymbolLayer(
        id = "earthquake-cluster-counts",
        source = earthquakes,
        filter = feature.has("point_count"),
        textField = feature["point_count_abbreviated"].convertToString(),
        // A font that the base style's glyphs provide
        textFont = const(listOf("Noto Sans Regular")),
      )

      // Points that are not in a cluster
      CircleLayer(
        id = "earthquake-points",
        source = earthquakes,
        filter = !feature.has("point_count"),
        radius = const(4.dp),
        color = const(Color(0xFF4FC3F7)),
        strokeWidth = const(1.dp),
        strokeColor = const(Color.White),
      )
      // #endregion layers
    }
  MaplibreMap(state = state)

  val clickState = rememberMapState {
    val earthquakes = rememberGeoJsonSource(GeoJsonData.Uri(EarthquakesUri)) { cluster = true }

    // #region zoom-on-click
    val mapState = checkNotNull(LocalMapState.current)
    val scope = rememberCoroutineScope()

    CircleLayer(
      id = "earthquake-clusters",
      source = earthquakes,
      filter = feature.has("point_count"),
      onClick = { features ->
        val cluster = features.first()
        scope.launch {
          val handle = mapState.style.sources[earthquakes] ?: return@launch
          val zoom = handle.getClusterExpansionZoom(cluster) ?: return@launch
          val center = (cluster.geometry as? Point)?.coordinates ?: return@launch
          mapState.animateCamera(CameraUpdate(center = center, zoom = zoom))
        }
        ClickResult.Consume
      },
    )
    // #endregion zoom-on-click
  }
  MaplibreMap(state = clickState)
}
