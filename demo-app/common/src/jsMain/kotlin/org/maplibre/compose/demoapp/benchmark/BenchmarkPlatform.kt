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

internal actual fun benchmarkCpu(active: Boolean) {}

/** Browsers expose no collection request. */
internal actual fun benchmarkCollectGarbage() {}

@Composable
internal actual fun ClassicAndroidBenchmark(
  fixture: BenchmarkFixture,
  onStatus: (String, Boolean) -> Unit,
) {
  UnsupportedClassicBenchmark(onStatus)
}
