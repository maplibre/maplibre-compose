@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.gte
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.neq
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.dsl.step
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
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
      radius = const(4.dp),
      color = const(Color.Red),
    )
    // #endregion constants

    // #region feature-data
    CircleLayer(
      id = "quakes-by-magnitude",
      source = earthquakes,
      // Below magnitude 4: 4 dp. From 4 up to 6: 8 dp. 6 and above: 16 dp.
      radius =
        step(
          input = feature["mag"].asNumber(),
          fallback = const(4.dp),
          4 to const(8.dp),
          6 to const(16.dp),
        ),
      // Blue where the "tsunami" property is 1, red everywhere else.
      color =
        switch(
          condition(test = feature["tsunami"] eq const(1), output = const(Color.Blue)),
          fallback = const(Color.Red),
        ),
    )
    // #endregion feature-data

    // #region zoom
    CircleLayer(
      id = "quakes-by-zoom",
      source = earthquakes,
      // 2 dp at zoom 5 and below, 8 dp at zoom 10 and above, in between on a straight line.
      radius = interpolate(linear(), zoom(), 5 to const(2.dp), 10 to const(8.dp)),
    )
    // #endregion zoom

    // #region filter
    CircleLayer(
      id = "strong-quakes",
      source = earthquakes,
      filter = feature["mag"].asNumber() gte const(5f),
    )
    // #endregion filter

    // #region missing-data
    // Every earthquake, sized by its "felt" value. A missing value counts as 0 reports.
    CircleLayer(
      id = "quakes-by-felt",
      source = earthquakes,
      radius =
        interpolate(
          linear(),
          feature["felt"].asNumber(const(0f)),
          0 to const(4.dp),
          100 to const(16.dp),
        ),
    )

    // Only the earthquakes that have a "felt" value.
    CircleLayer(id = "felt-quakes", source = earthquakes, filter = feature["felt"] neq nil())
    // #endregion missing-data

    // #region kotlin-or-expression
    // Kotlin chooses one stroke color for the whole layer while the content composes.
    val strokeColor = if (isSystemInDarkTheme()) Color.White else Color.Black

    CircleLayer(
      id = "quakes-themed",
      source = earthquakes,
      strokeColor = const(strokeColor),
      strokeWidth = const(1.dp),
      // The map chooses a fill color for each earthquake while it draws.
      color =
        switch(
          condition(test = feature["mag"].asNumber() gte const(5f), output = const(Color.Red)),
          fallback = const(Color.Yellow),
        ),
    )
    // #endregion kotlin-or-expression
  }
  MaplibreMap(state = state)
}
