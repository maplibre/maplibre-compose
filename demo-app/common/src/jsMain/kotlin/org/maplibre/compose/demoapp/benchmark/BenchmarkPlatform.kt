package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import kotlinx.browser.window
import org.maplibre.compose.map.MapUiOptions

@Composable
internal actual fun benchmarkLaunchConfig(): BenchmarkConfig? =
  BenchmarkConfig.parse(
    window.location.search
      .removePrefix("?")
      .split("&")
      .firstOrNull { it.startsWith("benchmark=") }
      ?.substringAfter("=")
  )

internal actual fun benchmarkMapOptions(config: BenchmarkConfig): MapUiOptions =
  MapUiOptions.Standard

internal actual fun benchmarkTrace(active: Boolean) {}

@Composable internal actual fun BenchmarkPlatformMetrics(active: Boolean) {}

internal actual fun benchmarkInput(sequence: Int, uptimeMillis: Long) {
  println("MAP_BENCHMARK INPUT_UNCALIBRATED $sequence $uptimeMillis")
}
