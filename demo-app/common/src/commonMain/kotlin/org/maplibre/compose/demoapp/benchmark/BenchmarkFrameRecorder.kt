package org.maplibre.compose.demoapp.benchmark

import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.maplibre.compose.map.MapEvent

/** Sentinel for "the first frame has not been seen"; elapsed time can legitimately be zero. */
private const val NoPreviousFrame = -1L

/** One map render frame, timed from the previous frame. */
@Serializable
internal data class FrameSample(
  @SerialName("interval_ms") val intervalMs: Double,
  @SerialName("encoding_ms") val encodingMs: Double? = null,
  @SerialName("rendering_ms") val renderingMs: Double? = null,
  @SerialName("draw_calls") val drawCalls: Long? = null,
  @SerialName("mode") val mode: String? = null,
)

/** Integrity report for the batched [FrameSample] lines. */
@Serializable
internal data class FrameStats(
  @SerialName("frames") val frames: Int,
  @SerialName("duration_ms") val durationMs: Double,
)

/**
 * Times [MapEvent.FrameRendered] events between [start] and [stop].
 *
 * The engine emits frames on its render threads, so the collector runs on [Dispatchers.Default] and
 * hands samples to a channel; [stop] drains it after the collector joins. That keeps the measured
 * timestamps off the busy composition thread and keeps its records behind a single-producer
 * channel.
 */
internal class BenchmarkFrameRecorder {
  private var job: Job? = null
  private var samples: Channel<FrameSample>? = null
  private var start = TimeSource.Monotonic.markNow()
  private var previousNanos = NoPreviousFrame

  /** Starts collecting from [events]. Must be paired with exactly one [stop]. */
  fun start(scope: CoroutineScope, events: Flow<MapEvent>) {
    check(job == null) { "Frame recorder is already running" }
    val samples = Channel<FrameSample>(Channel.UNLIMITED)
    this.samples = samples
    start = TimeSource.Monotonic.markNow()
    previousNanos = NoPreviousFrame
    job =
      scope.launch(Dispatchers.Default) {
        events.filterIsInstance<MapEvent.FrameRendered>().collect { event ->
          val now = start.elapsedNow().inWholeNanoseconds
          val previous = previousNanos
          previousNanos = now
          // The first event has no previous frame; it only anchors the interval clock.
          if (previous == NoPreviousFrame) return@collect
          val intervalMs = (now - previous) / 1e6
          val stats = event.stats
          samples.trySend(
            FrameSample(
              intervalMs = intervalMs,
              encodingMs = stats?.encodingTime?.inWholeMicroseconds?.div(1e3),
              renderingMs = stats?.renderingTime?.inWholeMicroseconds?.div(1e3),
              drawCalls = stats?.drawCallCount,
              mode = stats?.mode?.name?.lowercase(),
            )
          )
        }
      }
  }

  /** Stops collection and prints the frame statistics. Does nothing when never started. */
  suspend fun stop() {
    val samples = samples ?: return
    this.samples = null
    job?.cancelAndJoin()
    job = null
    val frames = buildList {
      while (true) add(samples.tryReceive().getOrNull() ?: break)
    }
    if (frames.isEmpty()) return
    val durationMs = start.elapsedNow().inWholeNanoseconds / 1e6
    println(
      "MAP_BENCHMARK FRAMESTATS " +
        BenchmarkJson.encodeToString(FrameStats(frames.size, durationMs))
    )
    // Batches stay below logcat's per-entry limit without overflowing its message queue.
    frames.chunked(32).forEach { batch ->
      println("MAP_BENCHMARK FRAMETIMES " + BenchmarkJson.encodeToString(batch))
    }
  }
}
