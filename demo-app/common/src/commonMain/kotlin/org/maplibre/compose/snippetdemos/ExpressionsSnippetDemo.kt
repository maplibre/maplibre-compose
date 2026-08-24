package org.maplibre.compose.snippetdemos

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.gt
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * Demonstrates the expressions guide: zoom the map and watch the earthquake circles resize with the
 * zoom interpolation, colored by magnitude.
 */
@Composable
fun ExpressionsSnippetDemo() {
  val camera =
    rememberCameraState(
      firstPosition =
        CameraPosition(target = Position(latitude = 37.0, longitude = -150.0), zoom = 2.0)
    )

  MaplibreMap(
    baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
    cameraState = camera,
    overlay =
      MapOverlay {
        include(MapOverlay.Default)
        Surface(
          modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
          shape = MaterialTheme.shapes.small,
          tonalElevation = 2.dp,
        ) {
          Text(
            "Zoom ${(cameraState.position.zoom * 10).toInt() / 10.0}:" +
              " circles grow with zoom, red above magnitude 5",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      },
  ) {
    val earthquakes =
      rememberGeoJsonSource(
        GeoJsonData.Uri("https://maplibre.org/maplibre-gl-js/docs/assets/earthquakes.geojson")
      )

    CircleLayer(
      id = "earthquakes",
      source = earthquakes,
      radius =
        interpolate(type = exponential(2f), input = zoom(), 2 to const(3.dp), 10 to const(24.dp)),
      color =
        switch(
          condition(test = feature["mag"].asNumber() gt const(5), output = const(Color.Red)),
          fallback = const(Color(0xFFFFB300)),
        ),
      opacity = const(0.7f),
    )
  }
}
