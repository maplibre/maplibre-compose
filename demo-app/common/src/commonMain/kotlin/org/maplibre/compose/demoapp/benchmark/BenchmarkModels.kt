package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.*
import org.maplibre.compose.benchmark.*
import org.maplibre.compose.map.MapUiOptions

class BenchmarkUiState {
  private var nextRunId = 0
  var runId by mutableStateOf(0)
    private set

  var running by mutableStateOf(false)
  var status by mutableStateOf("Ready")
  var configJson by mutableStateOf(BenchmarkConfig().encode())
  var config by mutableStateOf<BenchmarkConfig?>(null)
    private set

  fun requestRun(config: BenchmarkConfig) {
    if (running) return
    running = true
    status = "Starting"
    this.config = config
    runId = ++nextRunId
  }

  fun abandonRun() {
    runId = 0
    running = false
    status = "Ready"
    config = null
  }
}

@Composable internal expect fun benchmarkLaunchConfig(): BenchmarkConfig?

internal expect fun benchmarkMapOptions(config: BenchmarkConfig): MapUiOptions

/** Starts/stops the platform process CPU counter for the measured workload. */
internal expect fun benchmarkCpu(active: Boolean)

/**
 * Collects garbage before the measured pass. Warm-up garbage and, on Android, ART's timed post-fork
 * collection otherwise land inside some windows and not others.
 */
internal expect fun benchmarkCollectGarbage()
