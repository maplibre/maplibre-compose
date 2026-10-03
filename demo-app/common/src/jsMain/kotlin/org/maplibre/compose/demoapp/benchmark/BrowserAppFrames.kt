package org.maplibre.compose.demoapp.benchmark

import org.maplibre.compose.benchmark.BenchmarkUiFrames

/** Marks the fixed measurement window in Chromium's presentation trace. */
internal class BrowserAppFrames : BenchmarkUiFrames {
  override fun start(durationMillis: Long?) {
    js("performance.mark('MAP_BENCHMARK_APP_PRESENTATION_START')")
  }

  override fun end() {
    js("performance.mark('MAP_BENCHMARK_APP_PRESENTATION_END')")
  }

  override suspend fun stop() {}
}
