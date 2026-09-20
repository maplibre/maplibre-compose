package org.maplibre.compose.demoapp.benchmark

import android.os.Process
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import org.maplibre.compose.map.AndroidRenderMode
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.renderMode

@Composable
internal actual fun benchmarkLaunchConfig(): BenchmarkConfig? =
  BenchmarkConfig.parse(LocalActivity.current?.intent?.getStringExtra("benchmark"))

internal actual fun benchmarkMapOptions(config: BenchmarkConfig): MapUiOptions = MapUiOptions {
  renderMode =
    if (config.surface == "texture") AndroidRenderMode.Texture else AndroidRenderMode.Surface
}

private var startCpuMillis = 0L

internal actual fun benchmarkCpu(active: Boolean) {
  if (active) startCpuMillis = Process.getElapsedCpuTime()
  else println("MAP_BENCHMARK CPU ${Process.getElapsedCpuTime() - startCpuMillis}")
}
