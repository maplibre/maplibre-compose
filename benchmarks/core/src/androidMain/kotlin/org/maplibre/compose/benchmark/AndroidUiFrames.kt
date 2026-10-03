package org.maplibre.compose.benchmark

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Window [FrameMetrics]: each frame's total duration, the delay before the UI thread started it,
 * and its deadline. Observes actual window draws without requesting frames. A map rendering to its
 * own surface can produce no window frames during a measurement.
 */
class AndroidUiFrames(private val window: Window) : BenchmarkUiFrames {
  private var finish: (suspend () -> Unit)? = null
  @Volatile private var endNs: Long = Long.MAX_VALUE

  override fun start(durationMillis: Long?) {
    check(finish == null) { "UI frames are already being collected" }
    val worker = HandlerThread("BenchmarkFrameMetrics").apply { start() }
    val start = System.nanoTime()
    endNs = durationMillis?.let { start + it * 1_000_000 } ?: Long.MAX_VALUE
    val samples = mutableListOf<UiFrame>()
    var dropped = 0
    val callback = Window.OnFrameMetricsAvailableListener { _, metrics, loss ->
      // API 24/25 have no intended-vsync timestamp for exact window filtering.
      val intended =
        if (Build.VERSION.SDK_INT >= 26) metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        else -1L
      if (intended < 0 || intended in start until endNs) {
        dropped += loss
        samples +=
          UiFrame(
            metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6,
            metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION) / 1e6,
            if (Build.VERSION.SDK_INT >= 31)
              metrics.getMetric(FrameMetrics.DEADLINE).takeIf { it >= 0 }?.div(1e6)
            else null,
          )
      }
    }
    window.addOnFrameMetricsAvailableListener(callback, Handler(worker.looper))
    finish = {
      window.removeOnFrameMetricsAvailableListener(callback)
      // Metrics already queued on the worker land before the report.
      suspendCancellableCoroutine { continuation ->
        Handler(worker.looper).post {
          println("MAP_BENCHMARK UISTATS {\"frames\":${samples.size},\"dropped\":$dropped}")
          samples.chunked(16).forEach {
            println("MAP_BENCHMARK UIFRAMES " + BenchmarkJson.encodeToString(it))
          }
          worker.quitSafely()
          if (continuation.isActive) continuation.resume(Unit)
        }
      }
    }
  }

  override fun end() {
    endNs = minOf(endNs, System.nanoTime())
  }

  override suspend fun stop() {
    val action = finish ?: return
    finish = null
    if (endNs == Long.MAX_VALUE) end()
    action()
  }
}

@Serializable
private data class UiFrame(val total_ms: Double, val delay_ms: Double, val deadline_ms: Double?)
