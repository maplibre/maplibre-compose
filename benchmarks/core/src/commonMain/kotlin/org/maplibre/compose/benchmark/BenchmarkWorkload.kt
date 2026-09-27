package org.maplibre.compose.benchmark

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkloadReport(
  val operations: Int,
  @SerialName("submission_count") val submissionCount: Int = 0,
  @SerialName("completion_count") val completionCount: Int = 0,
  @SerialName("frame_count") val frameCount: Int = 0,
  @SerialName("close_count") val closeCount: Int = 0,
  @SerialName("close_ms") val closeMs: List<Double> = emptyList(),
  @SerialName("close_completion_ms") val closeCompletionMs: List<Double> = emptyList(),
  @SerialName("duration_ms") val durationMs: Double,
  @SerialName("submission_ms") val submissionMs: List<Double>,
  @SerialName("completion_ms") val completionMs: List<Double>,
  /** Milliseconds between consecutive frame callbacks of a frame-driven workload. */
  @SerialName("frame_interval_ms") val frameIntervalMs: List<Double>,
  @SerialName("completion_signal") val completionSignal: String?,
)

/** Time from map creation until the style loaded and until the first complete frame. */
@Serializable
data class StartupReport(
  @SerialName("style_ready_ms") val styleReadyMs: Double,
  @SerialName("first_frame_ms") val firstFrameMs: Double,
)

/** The same workload clock is used for full warm-up passes and measured repetitions. */
class BenchmarkWorkload(
  val durationMillis: Long,
  val nextFrame: suspend () -> Long,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  private val start = timeSource.markNow()
  private var operations = 0
  private val submissions = mutableListOf<Double>()
  private val completions = mutableListOf<Double>()
  private val frameIntervals = mutableListOf<Double>()
  private val closes = mutableListOf<Double>()
  private val closeCompletions = mutableListOf<Double>()

  fun markNow() = timeSource.markNow()

  /** Record the first close request and the time until owned resources finish cleanup. */
  suspend fun close(close: () -> Unit, awaitClosed: suspend () -> Unit) {
    val started = markNow()
    val caller =
      try {
        close()
        started.elapsedNow().inWholeNanoseconds / 1e6
      } finally {
        awaitClosed()
      }
    closes += caller
    closeCompletions += started.elapsedNow().inWholeNanoseconds / 1e6
  }

  var completionSignal: String? = null

  fun submitted(submissionMs: Double? = null, completionMs: Double? = null) {
    operations++
    submissionMs?.let(submissions::add)
    completionMs?.let(completions::add)
  }

  fun report() =
    WorkloadReport(
      operations = operations,
      durationMs = start.elapsedNow().inWholeNanoseconds / 1e6,
      submissionMs = submissions,
      completionMs = completions,
      frameIntervalMs = frameIntervals,
      completionSignal = completionSignal,
      closeMs = closes,
      closeCompletionMs = closeCompletions,
    )

  suspend fun idle() {
    delay((durationMillis - start.elapsedNow().inWholeMilliseconds).coerceAtLeast(0))
  }

  /**
   * Runs [block] once per frame with the workload's progress. The interval between frames is the UI
   * thread's frame pacing: a starved frame callback shows up as a long interval.
   */
  suspend fun frames(block: (Double) -> Unit) {
    val first = nextFrame()
    var previous = first
    while (start.elapsedNow().inWholeMilliseconds < durationMillis) {
      val now = nextFrame()
      if (start.elapsedNow().inWholeMilliseconds >= durationMillis) break
      frameIntervals += (now - previous) / 1e6
      previous = now
      block(((now - first) / 1e6 / durationMillis).coerceIn(0.0, 1.0))
      submitted()
    }
  }

  /** Skip missed slots rather than sending catch-up bursts. The block records its own operation. */
  suspend fun scheduled(rateHz: Double, block: suspend (Int) -> Unit) {
    val period = 1000.0 / rateHz
    var tick = 0
    var slot = 0
    while (start.elapsedNow().inWholeMilliseconds < durationMillis) {
      block(tick++)
      val elapsed = start.elapsedNow().inWholeNanoseconds / 1e6
      // Whole-millisecond delays can land a hair before a fractional slot; never reuse it.
      slot = maxOf(slot + 1, floor(elapsed / period).toInt() + 1)
      val next = slot * period
      delay(ceil(minOf(next, durationMillis.toDouble()) - elapsed).toLong().coerceAtLeast(0))
    }
    idle()
  }
}
