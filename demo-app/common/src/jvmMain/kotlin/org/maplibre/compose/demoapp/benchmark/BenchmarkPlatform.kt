package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import org.maplibre.compose.map.MapUiOptions

@Composable
internal actual fun benchmarkLaunchConfig(): BenchmarkConfig? =
  BenchmarkConfig.parse(System.getenv("MAP_BENCHMARK"))

internal actual fun benchmarkMapOptions(config: BenchmarkConfig): MapUiOptions =
  MapUiOptions.Standard

private var startCpuNanos = -1L

internal actual fun benchmarkTrace(active: Boolean) {
  val now = ProcessHandle.current().info().totalCpuDuration().orElse(null)?.toNanos() ?: -1L
  if (active) startCpuNanos = now
  else if (startCpuNanos >= 0 && now >= startCpuNanos)
    println("MAP_BENCHMARK CPU ${(now - startCpuNanos) / 1e6}")
}

@Composable internal actual fun BenchmarkPlatformMetrics(active: Boolean) {}

internal actual fun benchmarkInput(sequence: Int, uptimeMillis: Long) {
  println("MAP_BENCHMARK INPUT_UNCALIBRATED $sequence $uptimeMillis")
}
