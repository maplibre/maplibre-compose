package org.maplibre.compose.demoapp.benchmark

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import org.maplibre.compose.benchmark.*
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions

/** The Compose host of the map return workload: each return composes a new declared map. */
@Composable
internal fun BenchmarkReturn(fixture: BenchmarkFixture, onStatus: (String, Boolean) -> Unit) {
  var request by remember { mutableStateOf<ComposeBenchmarkDriver?>(null) }
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
          ComposeBenchmarkDriver(fixture).also { driver ->
            driver.state =
              DefaultMapRuntime.instance.createMapState(
                baseStyle = driver.baseStyle,
                cameraPosition = benchmarkCamera(-1.0),
                content = { driver.Content() },
              )
            request = driver
          }
        },
        unmount = { _ ->
          request = null
          repeat(2) { withFrameNanos {} }
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
private fun ReturnMap(fixture: BenchmarkFixture, driver: ComposeBenchmarkDriver) {
  driver.density = LocalDensity.current.density
  MaplibreMap(
    state = driver.state,
    modifier = Modifier.fillMaxSize(),
    uiOptions = benchmarkMapOptions(fixture.config),
    renderOptions = RenderOptions { maximumFps = fixture.config.maximumFps },
    overlay = {},
  )
}
