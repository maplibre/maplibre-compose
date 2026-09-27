package org.maplibre.compose.demoapp.benchmark

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.maplibre.compose.benchmark.*
import org.maplibre.compose.map.*

private class ReturnMount(val recorder: BenchmarkFrameRecorder) {
  val ready = CompletableDeferred<List<Double>>()
  var state: MapState? = null
}

@Composable
internal fun BenchmarkReturn(fixture: BenchmarkFixture, onStatus: (String, Boolean) -> Unit) {
  var request by remember { mutableStateOf<ReturnMount?>(null) }
  var cover by remember { mutableDoubleStateOf(0.0) }
  val uiFrames = rememberBenchmarkUiFrames()
  LaunchedEffect(fixture) {
    onStatus("Measuring map returns", true)
    runMapReturnBenchmark(
      fixture.config,
      nextFrame = { withFrameNanos { it } },
      mount = { recorder ->
        val next = ReturnMount(recorder)
        request = next
        withTimeout(15000) { next.ready.await() }
      },
      unmount = {
        val state = request?.state
        request = null
        repeat(2) { withFrameNanos {} }
        if (state != null) withTimeout(10000) { state.awaitClosed() }
      },
      cover = { cover = it },
      cpu = ::benchmarkCpu,
      collectGarbage = ::benchmarkCollectGarbage,
      uiFrames = uiFrames,
    )
    onStatus("Done. Results are in the benchmark log.", false)
  }
  Box(Modifier.fillMaxSize()) {
    request?.let { mount -> key(mount) { ReturnMap(fixture, mount) } }
    Box(
      Modifier.fillMaxSize()
        .graphicsLayer { translationX = size.width * cover.toFloat() }
        .background(Color(0xff303030))
    )
  }
}

@Composable
private fun ReturnMap(fixture: BenchmarkFixture, mount: ReturnMount) {
  val driver = remember { ComposeBenchmarkDriver(fixture) }
  val state =
    rememberMapState(baseStyle = driver.baseStyle, initialCameraPosition = benchmarkCamera(-1.0)) {
      driver.Content()
    }
  val density = LocalDensity.current.density
  SideEffect { mount.state = state }
  LaunchedEffect(state) {
    val events =
      launch(start = CoroutineStart.UNDISPATCHED) {
        state.events.collect { event ->
          when (event) {
            is MapEvent.FrameRendered ->
              mount.recorder.record(
                FrameSample(
                  encodingMs = event.stats?.encodingTime?.inWholeMicroseconds?.div(1e3),
                  renderingMs = event.stats?.renderingTime?.inWholeMicroseconds?.div(1e3),
                  drawCalls = event.stats?.drawCallCount,
                  mode = event.stats?.mode?.name?.lowercase(),
                )
              )
            is MapEvent.StyleLoadFailed ->
              mount.ready.completeExceptionally(IllegalStateException(event.reason))
            is MapEvent.SourceDataFailed -> mount.ready.completeExceptionally(event.cause)
            else -> Unit
          }
        }
      }
    try {
      snapshotFlow { state.style.loadState }.first { it is StyleLoadState.Ready }
      snapshotFlow { state.viewport }.first { it != null }
      driver.prepare(state)
      awaitSettled(state)
      val size = checkNotNull(state.viewport).size
      mount.ready.complete(
        listOf(size.width.value.toDouble(), size.height.value.toDouble(), density.toDouble())
      )
      awaitCancellation()
    } catch (e: Exception) {
      mount.ready.completeExceptionally(e)
      if (e is CancellationException) throw e
    } finally {
      events.cancel()
    }
  }
  MaplibreMap(
    state = state,
    modifier = Modifier.fillMaxSize(),
    uiOptions = benchmarkMapOptions(fixture.config),
    renderOptions = RenderOptions { maximumFps = fixture.config.maximumFps },
    overlay = {},
  )
}
