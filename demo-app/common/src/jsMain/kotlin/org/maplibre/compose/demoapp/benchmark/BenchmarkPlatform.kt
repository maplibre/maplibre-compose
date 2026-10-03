package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import org.maplibre.compose.benchmark.*
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
internal actual fun rememberBenchmarkUiFrames(): BenchmarkUiFrames = BenchmarkUiFrames.None

@Composable
internal actual fun BenchmarkRuntime(config: BenchmarkConfig, onStatus: (String, Boolean) -> Unit) {
  androidx.compose.runtime.LaunchedEffect(config) {
    println("MAP_BENCHMARK ERROR Runtime cache initialization requires MapLibre Native")
    onStatus("Runtime cache initialization requires MapLibre Native", false)
  }
}

internal actual val supportsRuntimeBenchmark: Boolean = false
