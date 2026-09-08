package org.maplibre.compose.demoapp.benchmark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.MapViewportInsets
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

private val Origin = Position(0.0, 0.0)

private fun camera(x: Double) = CameraPosition(target = Position(x * 0.002, 0.0), zoom = 15.0)

@Composable
internal fun BenchmarkMap(state: DemoAppState, viewportInsets: MapViewportInsets) {
  val ui = state.benchmark
  Box(Modifier.fillMaxSize().padding(viewportInsets.asPaddingValues())) {
    if (ui.runId == 0) Text("Choose settings and run the benchmark.")
    else
      key(ui.runId, state.selectedScenario) {
        val config = remember {
          BenchmarkConfig(state.selectedScenario, ui.surface, ui.maximumFps, ui.load)
        }
        BenchmarkRun(config) { status, running ->
          ui.status = status
          ui.running = running
        }
      }
  }
}

/** The same isolated scene is used by the demo panel and the command-line capture adapters. */
@Composable
internal fun BenchmarkRun(
  config: BenchmarkConfig,
  onStatus: (String, Boolean) -> Unit = { _, _ -> },
) {
  val style = remember(config.load) { benchmarkStyle(config.load) }
  val state = remember {
    DefaultMapRuntime.instance.createMapState(baseStyle = style, cameraPosition = camera(-1.0))
  }
  DisposableEffect(state) { onDispose { state.close() } }
  val density = LocalDensity.current.density
  var measuring by remember { mutableStateOf(false) }
  var inputSequence by remember { mutableStateOf(0) }
  BenchmarkPlatformMetrics(measuring)
  LaunchedEffect(state, config) {
    var traced = false
    var complete = false
    try {
      onStatus("Loading", true)
      withTimeout(15000) {
        snapshotFlow { state.style.loadState }
          .first { it is StyleLoadState.Ready || it is StyleLoadState.Failed }
        check(state.style.loadState is StyleLoadState.Ready) { "Benchmark style failed to load" }
        snapshotFlow { state.viewport }.first { it != null }
      }
      println("MAP_BENCHMARK START ${config.encode()} $density")
      onStatus("Warming up", true)
      delay(3000)
      // Run the same animation once before measurement to warm the map and Compose paths.
      state.animateCameraPosition(camera(1.0), 500.milliseconds)
      state.animateCameraPosition(camera(-1.0), 500.milliseconds)
      delay(500)
      benchmarkTrace(true)
      traced = true
      measuring = true
      println("MAP_BENCHMARK MEASURE")
      onStatus(if (config.scenario == BenchmarkScenario.Input) "Tap the map" else "Measuring", true)
      when (config.scenario) {
        BenchmarkScenario.Animation ->
          repeat(8) {
            state.animateCameraPosition(camera(if (it % 2 == 0) 1.0 else -1.0), 1500.milliseconds)
          }
        BenchmarkScenario.Setters -> {
          val start = withFrameNanos { it }
          while (true) {
            val seconds = (withFrameNanos { it } - start) / 1e9
            if (seconds >= 12.0) break
            state.setCameraPosition(camera(-sin(seconds * 2 * PI / 3 + PI / 2)))
          }
        }
        BenchmarkScenario.Input -> delay(12000)
      }
      complete = true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      println("MAP_BENCHMARK ERROR ${e.message}")
      onStatus(e.message ?: "Failed", false)
    } finally {
      measuring = false
      if (traced) benchmarkTrace(false)
      // Closing is part of a completed run; cancellation must also release its map runtime.
      withContext(NonCancellable) {
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
    MaplibreMap(
      state = state,
      uiOptions = benchmarkMapOptions(config),
      renderOptions = RenderOptions { maximumFps = config.maximumFps },
      overlay = {
        Canvas(Modifier.placedAt(Origin).size(44.dp)) {
          drawCircle(Color.Cyan, 20.dp.toPx(), style = Stroke(3.dp.toPx()))
        }
      },
    )
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
                state.setCameraPosition(camera(if (inputSequence % 2 == 1) 1.0 else -1.0))
              }
              event.changes.forEach { it.consume() }
            }
          }
        }
      )
    }
    Box(
      Modifier.padding(12.dp).size(16.dp).background(if (measuring) Color.Green else Color.DarkGray)
    )
  }
}

/** A network-free reference marker with optional deterministic gray geometry to increase work. */
internal fun benchmarkStyle(load: Int): BaseStyle {
  val points =
    (0 until load).joinToString(",") { i ->
      val x = ((i * 73 % 997) / 997.0 - 0.5) * 0.02
      val y = ((i * 137 % 991) / 991.0 - 0.5) * 0.01
      "[$x,$y]"
    }
  return BaseStyle.Json(
    """{"version":8,"sources":{"point":{"type":"geojson","data":{"type":"Point","coordinates":[0,0]}},"load":{"type":"geojson","data":{"type":"MultiPoint","coordinates":[$points]}}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#202020"}},{"id":"load","type":"circle","source":"load","paint":{"circle-radius":8,"circle-color":"#505050"}},{"id":"point","type":"circle","source":"point","paint":{"circle-radius":10,"circle-color":"#ff0000","circle-pitch-alignment":"map"}}]}"""
  )
}
