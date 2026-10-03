package org.maplibre.compose.benchmark

import kotlin.time.TimeSource
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Engine statistics from one render event. */
@Serializable
data class FrameSample(
  @SerialName("encoding_ms") val encodingMs: Double? = null,
  @SerialName("rendering_ms") val renderingMs: Double? = null,
  @SerialName("draw_calls") val drawCalls: Long? = null,
  @SerialName("mode") val mode: String? = null,
  @SerialName("frame_count") val frameCount: Long? = null,
  @SerialName("elapsed_ms") val elapsedMs: Double? = null,
)

/** Integrity report for the batched [FrameSample] lines. */
@Serializable
data class FrameStats(
  @SerialName("frames") val frames: Int,
  @SerialName("duration_ms") val durationMs: Double,
)

/**
 * Collects engine statistics from the render events a driver [record]s between [start] and [stop].
 * Counts completed map draws, independently of UI frame callbacks. Engine timing fields are
 * unavailable on the browser.
 */
class BenchmarkFrameRecorder(private val timeSource: TimeSource = TimeSource.Monotonic) {
  private var samples: Channel<FrameSample>? = null
  private var start = timeSource.markNow()
  private var durationMillis: Long? = null

  fun start(durationMillis: Long? = null) {
    check(samples == null) { "Frame recorder is already running" }
    samples = Channel(Channel.UNLIMITED)
    this.durationMillis = durationMillis
    start = timeSource.markNow()
  }

  fun record(sample: FrameSample) {
    val elapsed = start.elapsedNow().inWholeNanoseconds / 1e6
    if (durationMillis?.let { elapsed >= it } != true)
      samples?.trySend(sample.copy(elapsedMs = elapsed))
  }

  /** Stops collection and prints the frame statistics. Does nothing when never started. */
  fun stop(): FrameStats? {
    val samples = samples ?: return null
    this.samples = null
    val frames = buildList {
      while (true) add(samples.tryReceive().getOrNull() ?: break)
    }
    val elapsed = start.elapsedNow().inWholeNanoseconds / 1e6
    val durationMs = durationMillis?.let { minOf(elapsed, it.toDouble()) } ?: elapsed
    val stats = FrameStats(frames.size, durationMs)
    println("MAP_BENCHMARK FRAMESTATS " + BenchmarkJson.encodeToString(stats))
    // Batches stay below logcat's per-entry limit without overflowing its message queue.
    frames.chunked(16).forEach { batch ->
      println("MAP_BENCHMARK FRAMETIMES " + BenchmarkJson.encodeToString(batch))
    }
    return stats
  }
}
