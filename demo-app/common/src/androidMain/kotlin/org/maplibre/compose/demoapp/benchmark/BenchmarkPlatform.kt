package org.maplibre.compose.demoapp.benchmark

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.Trace
import android.view.FrameMetrics
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import org.json.JSONObject
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

internal actual fun benchmarkTrace(active: Boolean) {
  if (Build.VERSION.SDK_INT >= 29) {
    if (active) Trace.beginAsyncSection("MapBenchmark", 1)
    else Trace.endAsyncSection("MapBenchmark", 1)
  }
}

internal actual fun benchmarkInput(sequence: Int, uptimeMillis: Long) {
  // Android PointerInput uptime and screenrecord Winscope timestamps use different boot clocks.
  val eventBootNs =
    SystemClock.elapsedRealtimeNanos() - (SystemClock.uptimeMillis() - uptimeMillis) * 1_000_000L
  println("MAP_BENCHMARK INPUT $sequence $eventBootNs")
  Trace.beginSection("MapBenchmarkInput:$sequence")
  Trace.endSection()
}

@Composable
internal actual fun BenchmarkPlatformMetrics(active: Boolean) {
  val window = LocalActivity.current?.window
  DisposableEffect(window, active) {
    if (window == null || !active) return@DisposableEffect onDispose {}
    val worker = HandlerThread("benchmark-metrics").apply { start() }
    val handler = Handler(worker.looper)
    val frames = ArrayList<LongArray>()
    var lostReports = 0
    val listener =
      android.view.Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        lostReports += dropped
        // FrameMetrics uses the monotonic clock; Perfetto's trace uses boot time.
        val bootOffset = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
        frames +=
          longArrayOf(
            metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) + bootOffset,
            metrics.getMetric(FrameMetrics.TOTAL_DURATION),
            if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.GPU_DURATION) else -1L,
            if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L,
          )
      }
    window.addOnFrameMetricsAvailableListener(listener, handler)
    onDispose {
      window.removeOnFrameMetricsAvailableListener(listener)
      handler.post {
        val report =
          JSONObject()
            .put("scope", "Android app window; excludes independently rendered map GPU work")
            .put("frames", frames.size)
            .put("lost_reports", lostReports)
        // Batches stay below logcat's entry limit without overflowing its message queue.
        println("MAP_BENCHMARK WINDOW $report")
        frames.chunked(32).forEach { batch ->
          println("MAP_BENCHMARK FRAMES " + batch.joinToString(";") { it.joinToString(",") })
        }
        worker.quitSafely()
      }
    }
  }
}
