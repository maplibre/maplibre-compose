package org.maplibre.compose.demoapp.benchmark

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.maplibre.compose.benchmark.*
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.MapViewportInsets
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include

/** [viewportInsets] keeps the placeholder text out from under the panel. */
@Composable
internal fun BenchmarkMap(state: DemoAppState, viewportInsets: MapViewportInsets) {
  val ui = state.benchmark
  val runId = ui.runId
  val config = ui.config
  Box(Modifier.fillMaxSize().background(Color(0xff202020))) {
    if (config == null || runId == 0)
      Text(
        "Choose settings and run the benchmark.",
        Modifier.padding(viewportInsets.asPaddingValues()).align(Alignment.Center).padding(24.dp),
        color = Color.LightGray,
      )
    else
      key(runId) {
        BenchmarkRun(config) { status, running ->
          if (ui.runId == runId) {
            ui.status = status
            ui.running = running
          }
        }
      }
  }
}

/** Wait for the scene and viewport, warm up, reset, measure the workload, then close the map. */
@Composable
internal fun BenchmarkRun(
  config: BenchmarkConfig,
  onStatus: (String, Boolean) -> Unit = { _, _ -> },
) {
  if (config.scenario == BenchmarkScenario.RuntimeStartup) {
    BenchmarkRuntime(config, onStatus)
    return
  }
  var fixture by remember(config) { mutableStateOf<BenchmarkFixture?>(null) }
  LaunchedEffect(config) {
    try {
      require(
        config.implementation in
          setOf(BenchmarkImplementation.Imperative, BenchmarkImplementation.Declarative)
      ) {
        "Classic benchmarks run in their own app; use benchmark:run"
      }
      fixture = loadBenchmarkFixture(config)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      println("MAP_BENCHMARK ERROR ${e.message}")
      onStatus(e.message ?: "Fixture load failed", false)
    }
  }
  fixture?.let {
    if (config.scenario == BenchmarkScenario.MapReturn) BenchmarkReturn(it, onStatus)
    else BenchmarkPresentation(it, onStatus)
  }
}

@Composable
private fun BenchmarkPresentation(fixture: BenchmarkFixture, onStatus: (String, Boolean) -> Unit) {
  val config = fixture.config
  val driver = remember(fixture) { ComposeBenchmarkDriver(fixture) }
  val state =
    if (config.implementation == BenchmarkImplementation.Declarative) {
      rememberMapState(
        baseStyle = driver.baseStyle,
        initialCameraPosition = benchmarkCamera(-1.0),
      ) {
        driver.Content()
      }
    } else
      remember(fixture) {
        DefaultMapRuntime.instance.createMapState(
          baseStyle = driver.baseStyle,
          cameraPosition = benchmarkCamera(-1.0),
        )
      }
  driver.state = state
  driver.density = LocalDensity.current.density
  val uiFrames = rememberBenchmarkUiFrames()
  DisposableEffect(state) { onDispose { state.close() } }
  LaunchedEffect(state, config) {
    onStatus("Starting", true)
    val host =
      BenchmarkHost(::benchmarkCpu, ::benchmarkCollectGarbage, uiFrames) { onStatus(it, true) }
    val failure = runBenchmark(driver, host)
    onStatus(failure ?: "Done. Results are in the benchmark log.", false)
  }
  Box(Modifier.fillMaxSize().background(Color(0xff202020))) {
    Box(Modifier.fillMaxWidth().fillMaxHeight(driver.heightFraction).align(Alignment.Center)) {
      MaplibreMap(
        state = state,
        viewportInsets = driver.viewportInsets,
        uiOptions = benchmarkMapOptions(config),
        renderOptions = RenderOptions { maximumFps = config.maximumFps },
        overlay = {
          if (config.scenario == BenchmarkScenario.Overlays) include(MapOverlay.Default)
          else if (config.scene == BenchmarkScene.Basemap) include(MapOverlay.AttributionOnly)
        },
      )
    }
  }
}
