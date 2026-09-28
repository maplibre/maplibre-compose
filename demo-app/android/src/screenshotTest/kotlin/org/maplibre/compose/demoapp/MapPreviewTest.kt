package org.maplibre.compose.demoapp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource

// Android Studio renders @Preview with layoutlib, which cannot run MapLibre Native. These previews
// render the same way, so map code that starts the engine in a preview fails here.

@PreviewTest
@Preview(widthDp = 100, heightDp = 100)
@Composable
fun DefaultMapPreview() {
  MaplibreMap()
}

@PreviewTest
@Preview(widthDp = 100, heightDp = 100)
@Composable
fun MapWithContentPreview() {
  val state = rememberMapState {
    val points = rememberGeoJsonSource(GeoJsonData.Uri("https://example.com/points.geojson"))
    CircleLayer(id = "points", source = points, color = const(Color.Red))
  }
  Column {
    BasicText("zoom ${state.cameraPosition.zoom}")
    MaplibreMap(state = state)
  }
}
