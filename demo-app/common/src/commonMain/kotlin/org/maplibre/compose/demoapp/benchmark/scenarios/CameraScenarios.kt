package org.maplibre.compose.demoapp.benchmark.scenarios

import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.demoapp.benchmark.BenchmarkConfig
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.demoapp.benchmark.benchmarkCamera
import org.maplibre.compose.demoapp.benchmark.benchmarkStyle
import org.maplibre.compose.map.MapState
import org.maplibre.compose.style.BaseStyle

/** Native camera animation between two positions. */
internal class CameraAnimationScenario : BenchmarkScenarioSpec {
  override val scenario = BenchmarkScenario.Animation

  override fun style(config: BenchmarkConfig): BaseStyle = benchmarkStyle(config.load)

  override suspend fun workload(
    state: MapState,
    config: BenchmarkConfig,
    scene: BenchmarkSceneState,
  ) {
    repeat(8) {
      state.animateCamera(
        benchmarkCamera(if (it % 2 == 0) 1.0 else -1.0).toCameraUpdate(),
        CameraAnimation.Fly(1500.milliseconds),
      )
    }
  }
}

/** One camera setter call per Compose frame. */
internal class CameraSettersScenario : BenchmarkScenarioSpec {
  override val scenario = BenchmarkScenario.Setters

  override fun style(config: BenchmarkConfig): BaseStyle = benchmarkStyle(config.load)

  override suspend fun workload(
    state: MapState,
    config: BenchmarkConfig,
    scene: BenchmarkSceneState,
  ) {
    frameLoop { seconds ->
      state.setCameraPosition(benchmarkCamera(-sin(seconds * 2 * PI / 3 + PI / 2)))
    }
  }
}

/** Taps arrive through the platform event pipeline; the scaffold draws the tap target. */
internal class InputScenario : BenchmarkScenarioSpec {
  override val scenario = BenchmarkScenario.Input

  override fun style(config: BenchmarkConfig): BaseStyle = benchmarkStyle(config.load)

  override suspend fun workload(
    state: MapState,
    config: BenchmarkConfig,
    scene: BenchmarkSceneState,
  ) {
    delay(MeasurementMillis)
  }
}
