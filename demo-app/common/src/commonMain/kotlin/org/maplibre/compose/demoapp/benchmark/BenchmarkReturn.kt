package org.maplibre.compose.demoapp.benchmark

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.benchmark.*
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.map.rememberMapState

/** One request to compose a fresh map; the map's driver arrives once it is in composition. */
private class ReturnRequest {
  val driver = CompletableDeferred<ComposeBenchmarkDriver>()
}

/** The Compose host of the map return workload: each return composes a new declared map. */
@Composable
internal fun BenchmarkReturn(fixture: BenchmarkFixture, onStatus: (String, Boolean) -> Unit) {
  var request by remember { mutableStateOf<ReturnRequest?>(null) }
  var cover by remember { mutableDoubleStateOf(0.0) }
  val uiFrames = rememberBenchmarkUiFrames()
  LaunchedEffect(fixture) {
    onStatus("Starting", true)
    val host =
      BenchmarkHost(::benchmarkCpu, ::benchmarkCollectGarbage, uiFrames) { onStatus(it, true) }
    val failure =
      runMapReturnBenchmark(
        fixture.config,
        nextFrame = { withFrameNanos { it } },
        mount = {
          val next = ReturnRequest()
          request = next
          withTimeout(15000) { next.driver.await() }
        },
        unmount = { driver ->
          request = null
          repeat(2) { withFrameNanos {} }
          driver.close()
          driver.awaitClosed()
        },
        cover = { cover = it },
        host = host,
      )
    onStatus(failure ?: "Done. Results are in the benchmark log.", false)
  }
  Box(Modifier.fillMaxSize()) {
    request?.let { key(it) { ReturnMap(fixture, it) } }
    Box(
      Modifier.fillMaxSize()
        .graphicsLayer { translationX = size.width * cover.toFloat() }
        .background(Color(0xff303030))
    )
  }
}

@Composable
private fun ReturnMap(fixture: BenchmarkFixture, request: ReturnRequest) {
  val driver = remember { ComposeBenchmarkDriver(fixture) }
  val state =
    rememberMapState(baseStyle = driver.baseStyle, initialCameraPosition = benchmarkCamera(-1.0)) {
      driver.Content()
    }
  driver.state = state
  driver.density = LocalDensity.current.density
  DisposableEffect(state) { onDispose { state.close() } }
  SideEffect { request.driver.complete(driver) }
  MaplibreMap(
    state = state,
    modifier = Modifier.fillMaxSize(),
    uiOptions = benchmarkMapOptions(fixture.config),
    renderOptions = RenderOptions { maximumFps = fixture.config.maximumFps },
    overlay = {},
  )
}
