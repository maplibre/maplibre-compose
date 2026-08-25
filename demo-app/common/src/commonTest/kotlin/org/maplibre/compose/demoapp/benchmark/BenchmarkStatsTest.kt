package org.maplibre.compose.demoapp.benchmark

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameTimeStatsTest {
  @Test
  fun an_empty_list_is_zeros() {
    val stats = frameTimeStats(emptyList(), vsyncMs = 16.67)
    assertEquals(0, stats.frames)
    assertEquals(0, stats.droppedFrames)
    assertEquals(0.0, stats.avgMs)
  }

  @Test
  fun p95_uses_nearest_rank() {
    val frames = listOf(1.0, 2.0, 3.0, 4.0)
    val stats = frameTimeStats(frames, vsyncMs = 1.0)
    assertEquals(2.0, stats.p50Ms)
    assertEquals(4.0, stats.p95Ms)
    assertEquals(2.5, stats.avgMs)
  }

  @Test
  fun a_double_interval_counts_as_one_drop() {
    val stats = frameTimeStats(listOf(16.0, 33.0, 16.0), vsyncMs = 16.0)
    assertEquals(1, stats.droppedFrames)
  }
}

class GestureLatencyTest {
  @Test
  fun a_constant_lag_matches_the_delay() {
    val speedPxPerSec = 500.0
    val lagMs = 40.0
    val pointer =
      (0..50).map { step ->
        val tMs = step * 16.0
        TimedPoint(tMs, xPx = speedPxPerSec * tMs / 1000.0, yPx = 0.0)
      }
    val map = pointer.map { sample ->
      TimedPoint(sample.tMs, xPx = speedPxPerSec * (sample.tMs - lagMs) / 1000.0, yPx = 0.0)
    }
    val stats = estimateGestureLatency(pointer, map, minSpeedPxPerSec = 100.0)
    assertTrue(stats.samples > 0, "expected moving samples, got ${stats.samples}")
    assertTrue(abs(stats.medianLatencyMs - lagMs) < 2.0)
    assertTrue(abs(stats.medianTrailPx - speedPxPerSec * lagMs / 1000.0) < 1.0)
  }

  @Test
  fun a_still_pointer_does_not_invent_latency() {
    val pointer = listOf(TimedPoint(0.0, 10.0, 10.0), TimedPoint(100.0, 10.0, 10.0))
    val map = listOf(TimedPoint(50.0, 4.0, 10.0))
    val stats = estimateGestureLatency(pointer, map, minSpeedPxPerSec = 200.0)
    assertEquals(1, stats.samples)
    assertEquals(0.0, stats.medianLatencyMs)
    assertEquals(6.0, stats.medianTrailPx)
  }
}

class BenchmarkUiStateTest {
  @Test
  fun abandonRun_clears_a_leftover_run_token() {
    val ui = BenchmarkUiState()
    ui.requestRun()
    assertEquals(1, ui.runId)
    ui.abandonRun()
    assertEquals(0, ui.runId)
    assertEquals(false, ui.running)
    assertEquals(null, ui.report)
  }
}
