package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.*
import org.maplibre.compose.benchmark.*
import org.maplibre.compose.map.MapUiOptions

@Composable internal expect fun benchmarkLaunchConfig(): BenchmarkConfig?

internal expect fun benchmarkMapOptions(config: BenchmarkConfig): MapUiOptions

/** Starts/stops the platform process CPU counter for the measured workload. */
internal expect fun benchmarkCpu(active: Boolean)

/**
 * Collects garbage before the measured pass. Warm-up garbage and, on Android, ART's timed post-fork
 * collection otherwise land inside some windows and not others.
 */
internal expect fun benchmarkCollectGarbage()

/** Window frame timings for the platform, or [BenchmarkUiFrames.None]. */
@Composable internal expect fun rememberBenchmarkUiFrames(): BenchmarkUiFrames

/** Native-only runtime benchmark; no map or fixture is loaded. */
@Composable
internal expect fun BenchmarkRuntime(config: BenchmarkConfig, onStatus: (String, Boolean) -> Unit)

internal expect val supportsRuntimeBenchmark: Boolean
