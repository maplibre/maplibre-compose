package org.maplibre.compose.demoapp.benchmark.scenarios

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.maplibre.compose.demoapp.benchmark.BenchmarkConfig
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.demoapp.benchmark.PaddingParams
import org.maplibre.compose.demoapp.benchmark.benchmarkStyle
import org.maplibre.compose.map.MapState
import org.maplibre.compose.style.BaseStyle

/** Oscillates viewport padding each frame to drive relayout and recentering. */
internal class PaddingScenario : BenchmarkScenarioSpec {
  override val scenario = BenchmarkScenario.Padding

  override fun style(config: BenchmarkConfig): BaseStyle = benchmarkStyle(config.load)

  override suspend fun workload(
    state: MapState,
    config: BenchmarkConfig,
    scene: BenchmarkSceneState,
  ) {
    val params = config.params as PaddingParams
    val periodSeconds = params.periodMs / 1000.0
    frameLoop { seconds ->
      val phase = seconds * 2 * PI / periodSeconds
      val horizontal = (0.5 + 0.5 * sin(phase)) * params.amplitudeDp
      val vertical = (0.5 + 0.5 * cos(phase)) * params.amplitudeDp * 0.5
      // Asymmetric insets move the camera target: its screen position is (width + start - end) / 2.
      scene.viewportInsets =
        PaddingValues(
          start = horizontal.dp,
          top = vertical.dp,
          end = 0.dp,
          bottom = 0.dp,
        )
    }
  }
}
