package org.maplibre.compose.demoapp.benchmark.scenarios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlin.time.TimeSource
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.demoapp.benchmark.BenchmarkConfig
import org.maplibre.compose.demoapp.benchmark.BenchmarkPalette
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.demoapp.benchmark.StyleMutateParams
import org.maplibre.compose.demoapp.benchmark.generatedFeatureCollection
import org.maplibre.compose.demoapp.benchmark.styleColorHex
import org.maplibre.compose.demoapp.benchmark.styleMutationStyle
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.MaplibreComposable

/**
 * Mutates the style through its handles and the composition: existing layers get new paint and
 * layout properties, while composed layer and source pairs appear and disappear.
 */
internal class StyleMutateScenario : BenchmarkScenarioSpec {
  override val scenario = BenchmarkScenario.StyleMutate

  private var activePairs by mutableStateOf(1)

  override fun style(config: BenchmarkConfig): BaseStyle =
    BaseStyle.Json(
      styleMutationStyle(
        load = config.load,
        layers = (config.params as StyleMutateParams).pairs + BaseLayers,
      )
    )

  @Composable
  @MaplibreComposable
  override fun Content(state: MapState, config: BenchmarkConfig, scene: BenchmarkSceneState) {
    repeat(activePairs) { index ->
      val data =
        remember(index) { GeoJsonData.JsonString(generatedFeatureCollection(300, seed = index)) }
      val source = rememberGeoJsonSource(data)
      CircleLayer(
        id = "mutate-$index",
        source = source,
        color = const(BenchmarkPalette[index.mod(BenchmarkPalette.size)]),
        radius = const((4 + index).dp),
      )
    }
    ReferenceMarkerLayer()
  }

  override suspend fun workload(
    state: MapState,
    config: BenchmarkConfig,
    scene: BenchmarkSceneState,
  ) {
    val params = config.params as StyleMutateParams
    val periodMs = (1000.0 / params.rateHz).toLong().coerceAtLeast(1L)
    val start = TimeSource.Monotonic.markNow()
    var tick = 0
    while (start.remainingMeasurementMs() > 0) {
      activePairs = 1 + tick % params.pairs
      val color = JsonPrimitive(styleColorHex(tick))
      repeat(params.pairs + BaseLayers) { index ->
        state.style.layers["base-$index"]?.asMutable?.setPaintProperty("circle-color", color)
      }
      state.style.layers["base-0"]
        ?.asMutable
        ?.setLayoutProperty("visibility", JsonPrimitive(if (tick % 2 == 0) "visible" else "none"))
      tick++
      start.delayInMeasurement(periodMs)
    }
  }

  private companion object {
    const val BaseLayers = 4
  }
}
