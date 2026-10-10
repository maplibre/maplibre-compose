@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.VectorTileSource
import org.maplibre.compose.sources.getBaseSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable

private const val TrailUri = "https://maplibre.org/maplibre-gl-js/docs/assets/hike.geojson"

@Composable
fun Layers() {
  val geoJsonState = rememberMapState {
    // #region geojson
    val trail =
      rememberGeoJsonSource(
        GeoJsonData.Uri("https://maplibre.org/maplibre-gl-js/docs/assets/hike.geojson")
      )

    LineLayer(
      id = "trail-casing",
      source = trail,
      color = const(Color.White),
      width = const(6.dp),
    )
    LineLayer(
      id = "trail",
      source = trail,
      color = const(Color.Blue),
      width = const(4.dp),
    )
    // #endregion geojson
  }
  MaplibreMap(state = geoJsonState)

  val expressionState = rememberMapState {
    val trail = rememberGeoJsonSource(GeoJsonData.Uri(TrailUri))
    // #region expressions
    LineLayer(
      id = "trail",
      source = trail,
      color = const(Color.Blue),
      width =
        interpolate(
          type = exponential(1.5f),
          input = zoom(),
          10 to const(1.dp),
          18 to const(12.dp),
        ),
    )
    // #endregion expressions
  }
  MaplibreMap(state = expressionState)

  val anchorState = rememberMapState {
    val trail = rememberGeoJsonSource(GeoJsonData.Uri(TrailUri))
    // #region anchors
    Anchor.Below({ it.type == "symbol" }) {
      LineLayer(id = "trail", source = trail, color = const(Color.Blue), width = const(4.dp))
    }
    // #endregion anchors
  }
  MaplibreMap(state = anchorState)

  // #region base-source
  val baseSourceState =
    rememberMapState(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")) {
      val tiles = getBaseSource<VectorTileSource>(id = "openmaptiles")
      if (tiles != null) {
        CircleLayer(
          id = "all-pois",
          source = tiles,
          sourceLayer = "poi",
          color = const(Color.Red),
        )
      }
    }
  MaplibreMap(state = baseSourceState)
  // #endregion base-source
}

@Composable
fun LayerTransitions(selected: Boolean) {
  val state = rememberMapState {
    val trail = rememberGeoJsonSource(GeoJsonData.Uri(TrailUri))
    // #region transitions
    LineLayer(
      id = "trail",
      source = trail,
      color = const(if (selected) Color.Red else Color.Blue),
      colorTransition = TransitionOptions(duration = 500.milliseconds),
    )
    // #endregion transitions
  }
  MaplibreMap(state = state)
}

// #region custom-layer
@Composable
@MaplibreComposable
fun NgonLayer(
  id: String,
  source: Source,
  corners: Expression<FloatValue> = const(6f),
  radius: Expression<DpValue> = const(5.dp),
  color: Expression<ColorValue> = const(Color.Black),
) {
  Layer(id = id, type = "ngon", source = source) {
    paint("ngon-corners", corners)
    paint("ngon-radius", radius)
    paint("ngon-color", color)
  }
}

// #endregion custom-layer
