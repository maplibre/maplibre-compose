package org.maplibre.compose.demoapp.demos

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

object AnimatedLayerDemo : Demo {
  override val name = "Animated layers"

  override val region =
    BoundingBox(southwest = Position(-125.0, 24.0), northeast = Position(-66.0, 49.0))

  override val mapContentVisibilityState = mutableStateOf(true)

  @Composable
  override fun MapContent(state: DemoState, isOpen: Boolean) {
    val routeSource =
      rememberGeoJsonSource(
        data =
          GeoJsonData.Uri(
            "https://raw.githubusercontent.com/datanews/amtrak-geojson/refs/heads/master/amtrak-combined.geojson"
          ),
        options = GeoJsonOptions(tolerance = 0.1f),
      )

    val infiniteTransition = rememberInfiniteTransition()
    val animatedColor by
      infiniteTransition.animateColor(
        Color.hsl(0f, 1f, 0.5f),
        Color.hsl(0f, 1f, 0.5f),
        animationSpec =
          infiniteRepeatable(
            animation =
              keyframes {
                durationMillis = 10000
                for (i in 1..9) Color.hsl(i * 36f, 1f, 0.5f) at (i * 1000)
              }
          ),
      )

    Anchor.At(state.selectedStyle.anchorBelowSymbols) {
      LineLayer(
        id = "amtrak-routes",
        source = routeSource,
        color = const(animatedColor),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
        width =
          interpolate(
            type = exponential(1.2f),
            input = zoom(),
            7 to const(1.75.dp),
            20 to const(22.dp),
          ),
      )
    }
  }
}
