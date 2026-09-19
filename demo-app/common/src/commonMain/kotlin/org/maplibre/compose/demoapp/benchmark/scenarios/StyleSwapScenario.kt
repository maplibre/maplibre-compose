package org.maplibre.compose.demoapp.benchmark.scenarios

import kotlin.time.TimeSource
import org.maplibre.compose.demoapp.benchmark.BenchmarkConfig
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.demoapp.benchmark.StyleSwapParams
import org.maplibre.compose.demoapp.benchmark.generatedStyle
import org.maplibre.compose.map.MapState
import org.maplibre.compose.style.BaseStyle

/** Replaces the whole base style between two generated variants on an interval. */
internal class StyleSwapScenario : BenchmarkScenarioSpec {
  override val scenario = BenchmarkScenario.StyleSwap

  private lateinit var variants: List<BaseStyle>

  override fun style(config: BenchmarkConfig): BaseStyle {
    val params = config.params as StyleSwapParams
    variants =
      listOf(
        BaseStyle.Json(variant(config, params, palette = 0)),
        BaseStyle.Json(variant(config, params, palette = 3)),
      )
    return variants.first()
  }

  override suspend fun workload(
    state: MapState,
    config: BenchmarkConfig,
    scene: BenchmarkSceneState,
  ) {
    val params = config.params as StyleSwapParams
    val mutable = checkNotNull(state.style.asMutable) { "Base style is not mutable" }
    val start = TimeSource.Monotonic.markNow()
    var swaps = 0
    while (start.remainingMeasurementMs() > 0) {
      // The first interval runs on the initial style, so a slow first load cannot delay the first
      // analyzed frame; each later swap only starts while time remains to show the new style.
      start.delayInMeasurement(params.intervalMs.toLong())
      if (swaps < params.count && start.remainingMeasurementMs() > 0) {
        mutable.baseStyle = variants[(swaps + 1) % variants.size]
        swaps++
      }
    }
  }

  private fun variant(config: BenchmarkConfig, params: StyleSwapParams, palette: Int) =
    generatedStyle(
      load = config.load,
      sources = 2,
      features = params.features,
      layers = params.layers,
      palette = palette,
    )
}
