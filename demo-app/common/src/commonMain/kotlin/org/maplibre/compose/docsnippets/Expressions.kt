@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.gt
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.step
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.StyleComposition

@Composable
fun Expressions() {
  val composition = remember {
    StyleComposition {
      // #region constants
      val earthquakes =
        rememberGeoJsonSource(
          GeoJsonData.Uri("https://maplibre.org/maplibre-gl-js/docs/assets/earthquakes.geojson")
        )

      CircleLayer(
        id = "quakes-constant",
        source = earthquakes,
        radius = const(4.dp),
        color = const(Color.Red),
      )
      // #endregion constants

      // #region feature-data
      CircleLayer(
        id = "quakes-by-magnitude",
        source = earthquakes,
        radius =
          step(
            input = feature["mag"].asNumber(),
            fallback = const(4.dp),
            4 to const(8.dp),
            6 to const(16.dp),
          ),
        color =
          switch(
            condition(test = feature["mag"].asNumber() gt const(5), output = const(Color.Red)),
            fallback = const(Color.Yellow),
          ),
      )
      // #endregion feature-data

      // #region zoom
      CircleLayer(
        id = "quakes-by-zoom",
        source = earthquakes,
        radius =
          interpolate(
            type = exponential(2f),
            input = zoom(),
            5 to const(2.dp),
            10 to const(8.dp),
          ),
      )
      // #endregion zoom

      // #region filter
      CircleLayer(
        id = "large-quakes",
        source = earthquakes,
        filter = feature["mag"].asNumber() gt const(5),
      )
      // #endregion filter
    }
  }
  MaplibreMap(styleComposition = composition)
}
