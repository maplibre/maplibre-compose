package org.maplibre.compose.demoapp.benchmark.scenarios

import kotlin.math.PI
import kotlin.math.sin
import org.maplibre.compose.demoapp.benchmark.BenchmarkConfig
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.demoapp.benchmark.ResizeParams
import org.maplibre.compose.demoapp.benchmark.benchmarkStyle
import org.maplibre.compose.map.MapState
import org.maplibre.compose.style.BaseStyle

/** Resizes the map composition between two in-app extents. */
internal class ResizeScenario : BenchmarkScenarioSpec {
  override val scenario = BenchmarkScenario.Resize

  override fun style(config: BenchmarkConfig): BaseStyle = benchmarkStyle(config.load)

  override suspend fun workload(
    state: MapState,
    config: BenchmarkConfig,
    scene: BenchmarkSceneState,
  ) {
    val params = config.params as ResizeParams
    val periodSeconds = params.periodMs / 1000.0
    val minimum = params.minPercent / 100.0
    frameLoop { seconds ->
      val phase = seconds * 2 * PI / periodSeconds
      scene.sizeFraction = (minimum + (1 - minimum) * (0.5 + 0.5 * sin(phase))).toFloat()
    }
  }
}
