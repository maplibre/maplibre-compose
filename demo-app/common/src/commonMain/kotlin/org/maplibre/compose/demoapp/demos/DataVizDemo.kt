package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.OpenFreeMap
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToString
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.step
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.HeatmapLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.BoundingBox

object DataVizDemo : Demo {
  override val name = "Data visualization"
  override val description = "A month of earthquakes as points, a heatmap, or clusters."
  override val preferredStyle = OpenFreeMap.Positron

  // These bounds cross the antimeridian and frame the Pacific Ring of Fire.
  override val destination =
    DemoDestination.FitBounds(BoundingBox(west = 100.0, south = -60.0, east = -65.0, north = 70.0))

  /** USGS serves this feed with open CORS headers, so every platform can fetch it directly. */
  private const val FEED_URI =
    "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_month.geojson"

  private enum class Mode {
    Points,
    Heatmap,
    Clusters,
  }

  private var mode by mutableStateOf(Mode.Points)

  private val magnitude = feature["mag"].asNumber()

  @Composable
  override fun MapContent(mapState: MapState) {
    when (mode) {
      Mode.Points -> Points()
      Mode.Heatmap -> Heatmap()
      Mode.Clusters -> Clusters()
    }
  }

  @Composable
  private fun Points() {
    val source = rememberGeoJsonSource(GeoJsonData.Uri(FEED_URI))
    CircleLayer(
      id = "earthquake-points",
      source = source,
      radius =
        interpolate(linear(), magnitude, 2.5 to const(2.dp), 5 to const(8.dp), 8 to const(24.dp)),
      color =
        interpolate(
          linear(),
          magnitude,
          2.5 to const(Color(0xFFFFEB3B)),
          5 to const(Color(0xFFFF9800)),
          8 to const(Color(0xFFD32F2F)),
        ),
      opacity = const(0.7f),
      strokeWidth = const(1.dp),
      strokeColor = const(Color.White),
    )
  }

  @Composable
  private fun Heatmap() {
    val source = rememberGeoJsonSource(GeoJsonData.Uri(FEED_URI))
    HeatmapLayer(
      id = "earthquake-heatmap",
      source = source,
      weight = interpolate(linear(), magnitude, 2.5 to const(0.1f), 8 to const(1f)),
      radius = const(20.dp),
    )
  }

  @Composable
  private fun Clusters() {
    val source =
      rememberGeoJsonSource(
        GeoJsonData.Uri(FEED_URI),
        GeoJsonOptions(cluster = true, clusterRadius = 40, clusterMaxZoom = 10),
      )
    val pointCount = feature["point_count"].asNumber()

    CircleLayer(
      id = "earthquake-clusters",
      source = source,
      filter = feature.has("point_count"),
      radius = step(pointCount, const(14.dp), 25 to const(20.dp), 100 to const(28.dp)),
      color =
        step(
          pointCount,
          const(Color(0xFF4FC3F7)),
          25 to const(Color(0xFFFFB74D)),
          100 to const(Color(0xFFE57373)),
        ),
      opacity = const(0.85f),
    )

    SymbolLayer(
      id = "earthquake-cluster-counts",
      source = source,
      filter = feature.has("point_count"),
      textField = feature["point_count_abbreviated"].convertToString(),
      textFont = const(preferredStyle.textFont),
      textColor = const(Color.Black),
    )

    CircleLayer(
      id = "earthquake-unclustered",
      source = source,
      filter = !feature.has("point_count"),
      radius = const(4.dp),
      color = const(Color(0xFF4FC3F7)),
      strokeWidth = const(1.dp),
      strokeColor = const(Color.White),
    )
  }

  @Composable
  override fun Panel(state: DemoAppState) {
    SegmentedRow(
      label = "Render as",
      options = Mode.entries,
      selected = mode,
      optionLabel = { it.name },
      onSelect = { mode = it },
    )
  }
}
