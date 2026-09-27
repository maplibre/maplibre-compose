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
)

/** Integrity report for the batched [FrameSample] lines. */
@Serializable
data class FrameStats(
  @SerialName("frames") val frames: Int,
  @SerialName("duration_ms") val durationMs: Double,
)

/**
 * Collects engine statistics from the render events a driver [record]s between [start] and [stop].
 * The public event stream may drop events. These samples describe engine work, not display jank.
 * Engine timing fields are unavailable on the browser.
 */
class BenchmarkFrameRecorder {
  private var samples: Channel<FrameSample>? = null
  private var start = TimeSource.Monotonic.markNow()

  fun start() {
    check(samples == null) { "Frame recorder is already running" }
    samples = Channel(Channel.UNLIMITED)
    start = TimeSource.Monotonic.markNow()
  }

  fun record(sample: FrameSample) {
    samples?.trySend(sample)
  }

  /** Stops collection and prints the frame statistics. Does nothing when never started. */
  fun stop() {
    val samples = samples ?: return
    this.samples = null
    val frames = buildList {
      while (true) add(samples.tryReceive().getOrNull() ?: break)
    }
    val durationMs = start.elapsedNow().inWholeNanoseconds / 1e6
    println(
      "MAP_BENCHMARK FRAMESTATS " +
        BenchmarkJson.encodeToString(FrameStats(frames.size, durationMs))
    )
    // Batches stay below logcat's per-entry limit without overflowing its message queue.
    frames.chunked(16).forEach { batch ->
      println("MAP_BENCHMARK FRAMETIMES " + BenchmarkJson.encodeToString(batch))
    }
  }
}
