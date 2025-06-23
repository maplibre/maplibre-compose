package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.sargunv.maplibrecompose.compose.layer.Anchor
import dev.sargunv.maplibrecompose.compose.layer.LineLayer
import dev.sargunv.maplibrecompose.compose.source.rememberGeoJsonSource
import dev.sargunv.maplibrecompose.core.source.GeoJsonData
import dev.sargunv.maplibrecompose.core.source.GeoJsonOptions
import dev.sargunv.maplibrecompose.demoapp.DemoState
import dev.sargunv.maplibrecompose.expressions.dsl.const
import dev.sargunv.maplibrecompose.expressions.dsl.exponential
import dev.sargunv.maplibrecompose.expressions.dsl.interpolate
import dev.sargunv.maplibrecompose.expressions.dsl.zoom
import dev.sargunv.maplibrecompose.expressions.value.LineCap
import dev.sargunv.maplibrecompose.expressions.value.LineJoin
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Position

object AnimatedLayerDemo : Demo {
  override val name = "Animated layers"

  override val region =
    BoundingBox(southwest = Position(-125.0, 24.0), northeast = Position(-66.0, 49.0))

  override val mapContentVisibilityState = mutableStateOf(true)

  @Composable
  override fun MapContent(state: DemoState, isOpen: Boolean) {
    val routeSource =
      rememberGeoJsonSource(
        id = "amtrak-routes",
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
