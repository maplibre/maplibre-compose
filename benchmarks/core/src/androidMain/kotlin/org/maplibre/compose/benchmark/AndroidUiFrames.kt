package org.maplibre.compose.benchmark

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable

/** Window UI timings, including delay before the UI thread starts processing a frame. */
class AndroidUiFrames(private val window: Window) : BenchmarkUiFrames {
  private var thread: HandlerThread? = null
  private val choreographer = Choreographer.getInstance()
  private val heartbeat =
    object : Choreographer.FrameCallback {
      override fun doFrame(frameTimeNanos: Long) {
        window.decorView.invalidate()
        choreographer.postFrameCallback(this)
      }
    }

  override fun start() {
    check(thread == null)
    val worker = HandlerThread("BenchmarkFrameMetrics").apply { start() }
    thread = worker
    val start = System.nanoTime()
    val samples = mutableListOf<UiFrame>()
    var dropped = 0
    val callback = Window.OnFrameMetricsAvailableListener { _, metrics, loss ->
      // API 24/25 have no intended-vsync timestamp for exact window filtering.
      val intended =
        if (Build.VERSION.SDK_INT >= 26) metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        else -1L
      if (intended < 0 || intended >= start) {
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
    choreographer.postFrameCallback(heartbeat)
    window.addOnFrameMetricsAvailableListener(callback, Handler(worker.looper))
    finish = {
      choreographer.removeFrameCallback(heartbeat)
      window.removeOnFrameMetricsAvailableListener(callback)
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

  private var finish: (suspend () -> Unit)? = null

  override suspend fun stop() {
    val action = finish ?: return
    finish = null
    action()
    thread = null
  }
}

@Serializable
private data class UiFrame(val total_ms: Double, val delay_ms: Double, val deadline_ms: Double?)
