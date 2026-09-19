package org.maplibre.compose.demoapp.benchmark.scenarios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import org.maplibre.compose.demoapp.benchmark.BenchmarkConfig
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.demoapp.benchmark.StyleComplexParams
import org.maplibre.compose.demoapp.benchmark.benchmarkCamera
import org.maplibre.compose.demoapp.benchmark.generatedBitmap
import org.maplibre.compose.demoapp.benchmark.generatedStyle
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.MaplibreComposable

/** A heavy generated style under slow camera drift. */
internal class StyleComplexScenario : BenchmarkScenarioSpec {
  override val scenario = BenchmarkScenario.StyleComplex

  override fun style(config: BenchmarkConfig): BaseStyle {
    val params = config.params as StyleComplexParams
    return BaseStyle.Json(
      generatedStyle(
        load = config.load,
        sources = params.sources,
        features = params.features,
        layers = params.layers,
        symbols = params.layers / 4 + 1,
      )
    )
  }

  @Composable
  @MaplibreComposable
  override fun Content(state: MapState, config: BenchmarkConfig, scene: BenchmarkSceneState) {
    val symbols = (config.params as StyleComplexParams).layers / 4 + 1
    LaunchedEffect(state, symbols) {
      // Symbol layers stay inert until their icons exist; register them once the style is loaded.
      snapshotFlow { state.style.loadState }
        .first { it is StyleLoadState.Ready || it is StyleLoadState.Failed }
      if (state.style.loadState !is StyleLoadState.Ready) return@LaunchedEffect
      repeat(symbols) { index ->
        state.style.images.add("bench-icon-$index", generatedBitmap(24, index))
      }
    }
  }

  override suspend fun workload(
    state: MapState,
    config: BenchmarkConfig,
    scene: BenchmarkSceneState,
  ) {
    frameLoop { seconds ->
      state.setCameraPosition(benchmarkCamera(-sin(seconds * 2 * PI / 12)))
    }
  }
}
