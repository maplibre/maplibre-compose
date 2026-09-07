@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.kotlin.expr
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource

@Composable
fun Expressions() {
  val state = rememberMapState {
    // #region constants
    val earthquakes =
      rememberGeoJsonSource(
        GeoJsonData.Uri("https://maplibre.org/maplibre-gl-js/docs/assets/earthquakes.geojson")
      )

    CircleLayer(
      id = "quakes-constant",
      source = earthquakes,
      radius = expr { 4.dp },
      color = expr { Color.Red },
    )
    // #endregion constants

    // #region feature-data
    CircleLayer(
      id = "quakes-by-magnitude",
      source = earthquakes,
      radius =
        expr {
          val mag = feature.number("mag")
          when {
            mag >= 6 -> 16.dp
            mag >= 4 -> 8.dp
            else -> 4.dp
          }
        },
      color =
        expr {
          if (feature.number("mag") > 5) {
            Color.Red
          } else {
            Color.Yellow
          }
        },
    )
    // #endregion feature-data

    // #region zoom
    CircleLayer(
      id = "quakes-by-zoom",
      source = earthquakes,
      radius =
        expr {
          interpolate(exponential(2), zoom, 5 to 2.dp, 10 to 8.dp)
        },
    )
    // #endregion zoom

    // #region filter
    CircleLayer(
      id = "large-quakes",
      source = earthquakes,
      filter = expr { feature.number("mag") > 5 },
    )
    // #endregion filter
  }
  MaplibreMap(state = state)
}
