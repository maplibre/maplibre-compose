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
    val total = ArrayList<Double>()
    val gpu = ArrayList<Double>()
    var missedDeadlines = 0
    var deadlineFrames = 0
    var lostReports = 0
    val listener =
      android.view.Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        lostReports += dropped
        val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        if (duration < 0) return@OnFrameMetricsAvailableListener
        total += duration / 1e6
        val gpuDuration =
          if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.GPU_DURATION) else -1L
        gpu += if (gpuDuration >= 0) gpuDuration / 1e6 else -1.0
        if (Build.VERSION.SDK_INT >= 31) {
          val deadline = metrics.getMetric(FrameMetrics.DEADLINE)
          if (deadline >= 0) {
            deadlineFrames++
            if (duration > deadline) missedDeadlines++
          }
        }
      }
    window.addOnFrameMetricsAvailableListener(listener, handler)
    onDispose {
      window.removeOnFrameMetricsAvailableListener(listener)
      handler.post {
        val report =
          JSONObject()
            .put("scope", "Android app window; excludes independently rendered map GPU work")
            .put(
              "missed_deadlines",
              if (deadlineFrames > 0) missedDeadlines else JSONObject.NULL,
            )
            .put("deadline_frames", deadlineFrames)
            .put("lost_reports", lostReports)
        // One JSON line per frame avoids Android logcat's per-entry length limit.
        println("MAP_BENCHMARK WINDOW $report")
        total.forEachIndexed { index, value ->
          println("MAP_BENCHMARK FRAME $value ${gpu.getOrNull(index) ?: -1.0}")
        }
        worker.quitSafely()
      }
    }
  }
}
