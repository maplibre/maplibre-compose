package org.maplibre.compose.demoapp.benchmark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.MapViewportInsets
import org.maplibre.compose.demoapp.benchmark.scenarios.BenchmarkSceneState
import org.maplibre.compose.demoapp.benchmark.scenarios.benchmarkScenarioSpec
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.map.StyleLoadState

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

/**
 * The shared measurement protocol: wait for the style and viewport, warm up, mark the interval, run
 * the scenario workload, then close the map. Scenario content and workloads live in
 * `benchmark/scenarios`.
 */
@Composable
internal fun BenchmarkRun(
  config: BenchmarkConfig,
  onStatus: (String, Boolean) -> Unit = { _, _ -> },
) {
  val spec = remember(config) { benchmarkScenarioSpec(config.scenario) }
  val scene = remember(config) { BenchmarkSceneState() }
  val state =
    remember(config) {
      DefaultMapRuntime.instance.createMapState(
        baseStyle = spec.style(config),
        cameraPosition = benchmarkCamera(-1.0),
      ) {
        spec.Content(LocalMapState.current!!, config, scene)
      }
    }
  val recorder = remember(config) { BenchmarkFrameRecorder() }
  DisposableEffect(state) { onDispose { state.close() } }
  val density = LocalDensity.current.density
  var measuring by remember { mutableStateOf(false) }
  var inputSequence by remember { mutableStateOf(0) }
  var collectingMetrics by remember { mutableStateOf(true) }
  BenchmarkPlatformMetrics(collectingMetrics)
  LaunchedEffect(state, config) {
    var traced = false
    var recorded = false
    var complete = false
    try {
      onStatus("Loading", true)
      try {
        withTimeout(15000) {
          snapshotFlow { state.style.loadState }
            .first { it is StyleLoadState.Ready || it is StyleLoadState.Failed }
          check(state.style.loadState is StyleLoadState.Ready) {
            "Benchmark style failed to load"
          }
          snapshotFlow { state.viewport }.first { it != null }
        }
      } catch (e: TimeoutCancellationException) {
        // A timeout is a workload failure, not caller cancellation; report it as an error.
        error("Timed out waiting for the style and viewport")
      }
      println("MAP_BENCHMARK START ${config.encode()} $density")
      onStatus("Warming up", true)
      delay(3000)
      // Run the same camera path once before measurement to warm the map and Compose paths.
      state.animateCamera(
        benchmarkCamera(1.0).toCameraUpdate(),
        CameraAnimation.Fly(500.milliseconds),
      )
      state.animateCamera(
        benchmarkCamera(-1.0).toCameraUpdate(),
        CameraAnimation.Fly(500.milliseconds),
      )
      delay(500)
      benchmarkTrace(true)
      traced = true
      recorder.start(this, state.events)
      recorded = true
      measuring = true
      println("MAP_BENCHMARK MEASURE")
      onStatus(if (config.scenario == BenchmarkScenario.Input) "Tap the map" else "Measuring", true)
      spec.workload(state, config, scene)
      complete = true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      println("MAP_BENCHMARK ERROR ${e.message}")
      onStatus(e.message ?: "Failed", false)
    } finally {
      measuring = false
      if (traced) benchmarkTrace(false)
      collectingMetrics = false
      // Closing is part of a completed run; cancellation must also release its map runtime.
      withContext(NonCancellable) {
        // Stopping suspends, so it must run even when cancellation reaches this block.
        if (recorded) recorder.stop()
        if (complete)
          delay(200) // Let the inactive gate reach the compositor before removing the map.
        state.close()
        withTimeout(10000) { state.awaitClosed() }
      }
      println("MAP_BENCHMARK CLOSED")
      if (complete) {
        println("MAP_BENCHMARK DONE $inputSequence")
        onStatus("Done. Analyze the capture for measurements.", false)
      }
    }
  }
  Box(Modifier.fillMaxSize().background(Color(0xff202020))) {
    Box(Modifier.fillMaxSize(scene.sizeFraction).align(Alignment.Center)) {
      MaplibreMap(
        state = state,
        viewportInsets = scene.viewportInsets,
        uiOptions = benchmarkMapOptions(config),
        renderOptions = RenderOptions { maximumFps = config.maximumFps },
        overlay = {
          Canvas(Modifier.placedAt(BenchmarkOrigin).size(44.dp)) {
            drawCircle(Color.Cyan, 20.dp.toPx(), style = Stroke(3.dp.toPx()))
          }
        },
      )
    }
    // Input goes through the platform event pipeline. Each press causes a discrete, visible step.
    if (config.scenario == BenchmarkScenario.Input) {
      Box(
        Modifier.fillMaxSize().pointerInput(state, measuring) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              if (measuring && event.type == PointerEventType.Press) {
                inputSequence++
                benchmarkInput(inputSequence, event.changes.first().uptimeMillis)
                state.setCameraPosition(benchmarkCamera(if (inputSequence % 2 == 1) 1.0 else -1.0))
              }
              event.changes.forEach { it.consume() }
            }
          }
        }
      )
    }
    Row(Modifier.padding(12.dp)) {
      Box(Modifier.size(16.dp).background(if (measuring) Color.Green else Color.DarkGray))
      Spacer(Modifier.width(4.dp))
      Box(Modifier.size(16.dp).background(Color.Magenta))
    }
  }
}
