@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.gte
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.MaplibreComposable

@Composable
fun CompositionBasePlusContent() {
  // #region base-plus-content
  val state =
    rememberMapState(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")) {
      val earthquakes =
        rememberGeoJsonSource(
          GeoJsonData.Uri("https://maplibre.org/maplibre-gl-js/docs/assets/earthquakes.geojson")
        )
      CircleLayer(id = "earthquakes", source = earthquakes, color = const(Color.Red))
    }
  MaplibreMap(state = state)
  // #endregion base-plus-content
}

// #region recomposition
@Composable
fun EarthquakeMap(showStrongOnly: Boolean, dotColor: Color) {
  val state =
    rememberMapState(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")) {
      val earthquakes =
        rememberGeoJsonSource(
          GeoJsonData.Uri("https://maplibre.org/maplibre-gl-js/docs/assets/earthquakes.geojson")
        )
      CircleLayer(id = "earthquakes", source = earthquakes, color = const(dotColor))
      if (showStrongOnly) {
        CircleLayer(
          id = "strong-earthquakes",
          source = earthquakes,
          filter = feature["mag"].asNumber() gte const(5f),
          radius = const(10.dp),
          color = const(Color.Yellow),
        )
      }
    }
  MaplibreMap(state = state)
}

// #endregion recomposition

// #region shared-content
@Composable
@MaplibreComposable
fun EarthquakeLayers() {
  val earthquakes =
    rememberGeoJsonSource(
      GeoJsonData.Uri("https://maplibre.org/maplibre-gl-js/docs/assets/earthquakes.geojson")
    )
  CircleLayer(id = "earthquakes", source = earthquakes, color = const(Color.Red))
}

@Composable
fun EarthquakeMapAndSnapshotter() {
  val baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")
  val state = rememberMapState(baseStyle = baseStyle) { EarthquakeLayers() }
  val snapshotter = remember {
    DefaultMapRuntime.instance.createSnapshotter(baseStyle = baseStyle) { EarthquakeLayers() }
  }
  DisposableEffect(snapshotter) { onDispose { snapshotter.close() } }
  MaplibreMap(state = state)
}

// #endregion shared-content

@Composable
fun CompositionHandles() {
  // #region handles
  val state =
    rememberMapState(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"))

  // Null until the style is ready. Each loaded style publishes a new handle.
  val buildings3d = state.style.layers["building-3d"]
  LaunchedEffect(buildings3d) {
    buildings3d?.asMutable?.setLayoutProperty("visibility", JsonPrimitive("none"))
  }

  MaplibreMap(state = state)
  // #endregion handles
}
