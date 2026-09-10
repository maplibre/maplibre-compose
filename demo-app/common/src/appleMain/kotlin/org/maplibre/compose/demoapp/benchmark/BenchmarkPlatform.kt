package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import org.maplibre.compose.map.MapUiOptions
import platform.Foundation.NSProcessInfo
import platform.posix.RUSAGE_SELF
import platform.posix.getrusage
import platform.posix.rusage

@Composable
internal actual fun benchmarkLaunchConfig(): BenchmarkConfig? =
  BenchmarkConfig.parse(NSProcessInfo.processInfo.environment["MAP_BENCHMARK"] as? String)

internal actual fun benchmarkMapOptions(config: BenchmarkConfig): MapUiOptions =
  MapUiOptions.Standard

@OptIn(ExperimentalForeignApi::class)
private fun processCpuNanos(): Long = memScoped {
  val usage = alloc<rusage>()
  if (getrusage(RUSAGE_SELF, usage.ptr) != 0) return@memScoped -1L
  (usage.ru_utime.tv_sec + usage.ru_stime.tv_sec) * 1_000_000_000L +
    (usage.ru_utime.tv_usec.toLong() + usage.ru_stime.tv_usec) * 1000L
}

private var startCpuNanos = -1L

internal actual fun benchmarkTrace(active: Boolean) {
  val now = processCpuNanos()
  if (active) startCpuNanos = now
  else if (startCpuNanos >= 0 && now >= startCpuNanos)
    println("MAP_BENCHMARK CPU ${(now - startCpuNanos) / 1e6}")
}

@Composable internal actual fun BenchmarkPlatformMetrics(active: Boolean) {}

internal actual fun benchmarkInput(sequence: Int, uptimeMillis: Long) {
  println("MAP_BENCHMARK INPUT_UNCALIBRATED $sequence $uptimeMillis")
}
