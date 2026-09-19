package org.maplibre.compose.demoapp.benchmark.scenarios

import androidx.compose.runtime.withFrameNanos
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class WorkloadReport(
  val version: Int = 2,
  val operations: Int,
  @SerialName("submission_count") val submissionCount: Int = 0,
  @SerialName("completion_count") val completionCount: Int = 0,
  @SerialName("duration_ms") val durationMs: Double,
  @SerialName("submission_ms") val submissionMs: List<Double>,
  @SerialName("completion_ms") val completionMs: List<Double>,
  @SerialName("completion_signal") val completionSignal: String?,
)

/** The same workload clock is used for full warm-up passes and measured repetitions. */
internal class BenchmarkWorkload(val durationMillis: Long) {
  private val start = TimeSource.Monotonic.markNow()
  private var operations = 0
  private val submissions = mutableListOf<Double>()
  private val completions = mutableListOf<Double>()
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
      completionSignal = completionSignal,
    )

  suspend fun idle() {
    delay((durationMillis - start.elapsedNow().inWholeMilliseconds).coerceAtLeast(0))
  }

  suspend fun frames(block: (Double) -> Unit) {
    val first = withFrameNanos { it }
    while (start.elapsedNow().inWholeMilliseconds < durationMillis) {
      val now = withFrameNanos { it }
      if (start.elapsedNow().inWholeMilliseconds >= durationMillis) break
      block(((now - first) / 1e6 / durationMillis).coerceIn(0.0, 1.0))
      submitted()
    }
  }

  /** Skip missed slots rather than sending catch-up bursts. The block records its own operation. */
  suspend fun scheduled(rateHz: Double, block: suspend (Int) -> Unit) {
    val period = 1000.0 / rateHz
    var tick = 0
    while (start.elapsedNow().inWholeMilliseconds < durationMillis) {
      block(tick++)
      val elapsed = start.elapsedNow().inWholeMilliseconds
      val next = (floor(elapsed / period) + 1) * period
      delay(ceil(minOf(next, durationMillis.toDouble()) - elapsed).toLong().coerceAtLeast(0))
    }
    idle()
  }
}
