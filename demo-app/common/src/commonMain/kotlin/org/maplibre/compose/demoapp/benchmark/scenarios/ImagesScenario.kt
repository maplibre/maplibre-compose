package org.maplibre.compose.demoapp.benchmark.scenarios

import kotlin.time.TimeSource
import org.maplibre.compose.demoapp.benchmark.BenchmarkConfig
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.demoapp.benchmark.ImagesParams
import org.maplibre.compose.demoapp.benchmark.generatedBitmap
import org.maplibre.compose.demoapp.benchmark.generatedStyle
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MutableStyleImageHandle
import org.maplibre.compose.style.BaseStyle

/** Adds and removes generated style images whose symbol layers reference them. */
internal class ImagesScenario : BenchmarkScenarioSpec {
  override val scenario = BenchmarkScenario.Images

  override fun style(config: BenchmarkConfig): BaseStyle {
    val params = config.params as ImagesParams
    return BaseStyle.Json(
      generatedStyle(
        load = config.load,
        sources = 1,
        features = 200,
        layers = 0,
        symbols = params.count,
      )
    )
  }

  override suspend fun workload(
    state: MapState,
    config: BenchmarkConfig,
    scene: BenchmarkSceneState,
  ) {
    val params = config.params as ImagesParams
    val handles = mutableMapOf<Int, MutableStyleImageHandle>()
    val start = TimeSource.Monotonic.markNow()
    var tick = 0
    while (start.remainingMeasurementMs() > 0) {
      val index = tick % params.count
      handles.remove(index)?.remove()
      handles[index] =
        state.style.images.add("bench-icon-$index", generatedBitmap(params.sizePx, seed = tick))
      tick++
      start.delayInMeasurement(params.intervalMs.toLong())
    }
  }
}
