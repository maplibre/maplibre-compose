package org.maplibre.compose.demoapp.benchmark.scenarios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlin.time.TimeSource
import org.maplibre.compose.demoapp.benchmark.BenchmarkConfig
import org.maplibre.compose.demoapp.benchmark.BenchmarkPalette
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.demoapp.benchmark.GeojsonUpdateParams
import org.maplibre.compose.demoapp.benchmark.benchmarkStyle
import org.maplibre.compose.demoapp.benchmark.generatedFeatureCollection
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.MaplibreComposable

/** Replaces one GeoJSON source's data at a fixed rate. */
internal class GeojsonUpdateScenario : BenchmarkScenarioSpec {
  override val scenario = BenchmarkScenario.GeojsonUpdate

  private var data by
    mutableStateOf(GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""))

  override fun style(config: BenchmarkConfig): BaseStyle =
    benchmarkStyle(config.load, reference = false)

  @Composable
  @MaplibreComposable
  override fun Content(state: MapState, config: BenchmarkConfig, scene: BenchmarkSceneState) {
    // The initial install uses the configured size so warm-up matches the measured data.
    LaunchedEffect(config) {
      data =
        GeoJsonData.JsonString(
          generatedFeatureCollection((config.params as GeojsonUpdateParams).features, seed = 0)
        )
    }
    val source = rememberGeoJsonSource(data)
    CircleLayer(
      id = "updates",
      source = source,
      color = const(BenchmarkPalette.first()),
      radius = const(6.dp),
    )
    ReferenceMarkerLayer()
  }

  override suspend fun workload(
    state: MapState,
    config: BenchmarkConfig,
    scene: BenchmarkSceneState,
  ) {
    val params = config.params as GeojsonUpdateParams
    val periodMs = (1000.0 / params.rateHz).toLong().coerceAtLeast(1L)
    val start = TimeSource.Monotonic.markNow()
    var seed = 1
    while (start.remainingMeasurementMs() > 0) {
      data = GeoJsonData.JsonString(generatedFeatureCollection(params.features, seed++))
      start.delayInMeasurement(periodMs)
    }
  }
}
