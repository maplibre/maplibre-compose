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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.MapViewportInsets
import org.maplibre.compose.demoapp.benchmark.scenarios.BenchmarkWorkload
import org.maplibre.compose.demoapp.benchmark.scenarios.WorkloadReport
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.rememberMapState

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
  var fixture by remember(config) { mutableStateOf<BenchmarkFixture?>(null) }
  LaunchedEffect(config) {
    try {
      fixture = loadBenchmarkFixture(config)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      println("MAP_BENCHMARK ERROR ${e.message}")
      onStatus(e.message ?: "Fixture load failed", false)
    }
  }
  fixture?.let {
    if (config.implementation == BenchmarkImplementation.ClassicAndroid)
      ClassicAndroidBenchmark(it, onStatus)
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
  val recorder = remember(config) { BenchmarkFrameRecorder() }
  DisposableEffect(state) { onDispose { state.close() } }
  val density = LocalDensity.current.density
  LaunchedEffect(state, config) {
    var countingCpu = false
    var recorded = false
    var complete = false
    var workloadReport: WorkloadReport? = null
    val failures =
      launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        state.events.collect { event ->
          val failure =
            when (event) {
              is org.maplibre.compose.map.MapEvent.StyleLoadFailed -> event.reason
              is org.maplibre.compose.map.MapEvent.SourceDataFailed ->
                event.cause.message ?: "Source load failed"
              else -> null
            }
          if (failure != null) {
            println("MAP_BENCHMARK ERROR $failure")
            error(failure)
          }
        }
      }
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
      driver.prepare(state)
      awaitSettled(state)
      println("MAP_BENCHMARK START ${config.encode()}")
      val size = checkNotNull(state.viewport).size
      println("MAP_BENCHMARK VIEWPORT [${size.width.value},${size.height.value},$density]")
      onStatus("Warming up", true)
      driver.run(state, BenchmarkWorkload(config.durationMs))
      driver.reset(state)
      // Same order as the classic driver: the status frame and collection precede the counter.
      onStatus("Measuring", true)
      benchmarkCollectGarbage()
      benchmarkCpu(true)
      countingCpu = true
      recorder.start(this, state.events)
      recorded = true
      println("MAP_BENCHMARK MEASURE")
      val workload = BenchmarkWorkload(config.durationMs)
      driver.run(state, workload)
      workloadReport = workload.report()
      complete = true
    } catch (e: TimeoutCancellationException) {
      println("MAP_BENCHMARK ERROR Timed out waiting for workload completion")
      onStatus("Workload timed out", false)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      println("MAP_BENCHMARK ERROR ${e.message}")
      onStatus(e.message ?: "Failed", false)
    } finally {
      failures.cancel()
      if (countingCpu) benchmarkCpu(false)
      // Closing is part of a completed run; cancellation must also release its map runtime.
      withContext(NonCancellable) {
        // Stopping suspends, so it must run even when cancellation reaches this block.
        if (recorded) recorder.stop()
        workloadReport?.printResult()
        state.close()
        withTimeout(10000) { state.awaitClosed() }
      }
      println("MAP_BENCHMARK CLOSED")
      if (complete) {
        println("MAP_BENCHMARK DONE")
        onStatus("Done. Results are in the benchmark log.", false)
      }
    }
  }
  Box(Modifier.fillMaxSize().background(Color(0xff202020))) {
    Box(Modifier.fillMaxWidth().fillMaxHeight(driver.heightFraction).align(Alignment.Center)) {
      MaplibreMap(
        state = state,
        viewportInsets = driver.viewportInsets,
        uiOptions = benchmarkMapOptions(config),
        renderOptions = RenderOptions { maximumFps = config.maximumFps },
      )
    }
  }
}

internal fun WorkloadReport.printResult() {
  submissionMs.chunked(32).forEach { batch ->
    println("MAP_BENCHMARK SUBMISSIONS " + BenchmarkJsonWithDefaults.encodeToString(batch))
  }
  completionMs.chunked(32).forEach { batch ->
    println("MAP_BENCHMARK COMPLETIONS " + BenchmarkJsonWithDefaults.encodeToString(batch))
  }
  println(
    "MAP_BENCHMARK WORKLOAD " +
      BenchmarkJsonWithDefaults.encodeToString(
        copy(
          submissionMs = emptyList(),
          completionMs = emptyList(),
          submissionCount = submissionMs.size,
          completionCount = completionMs.size,
        )
      )
  )
}
