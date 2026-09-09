package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import org.maplibre.compose.map.MapUiOptions

@Composable
internal actual fun benchmarkLaunchConfig(): BenchmarkConfig? =
  BenchmarkConfig.parse(benchmarkQuery())

private fun benchmarkQuery(): String? =
  js("new URLSearchParams(window.location.search).get('benchmark')")

internal actual fun benchmarkMapOptions(config: BenchmarkConfig): MapUiOptions =
  MapUiOptions.Standard

internal actual fun benchmarkTrace(active: Boolean) {}

@Composable internal actual fun BenchmarkPlatformMetrics(active: Boolean) {}

internal actual fun benchmarkInput(sequence: Int, uptimeMillis: Long) {
  println("MAP_BENCHMARK INPUT_UNCALIBRATED $sequence $uptimeMillis")
}
