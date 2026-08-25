package org.maplibre.compose.demoapp.benchmark

import kotlin.concurrent.Volatile
import kotlin.math.hypot
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred

/**
 * Pointer samples and projected-pin samples for one drag. Everything here is written from the
 * Compose clock, so the two traces share a timeline.
 */
class GestureLatencyTracker {
  private val pointer = ArrayList<TimedPoint>(2048)
  private val map = ArrayList<TimedPoint>(2048)
  private var origin: TimedPoint? = null
  private var drag: CompletableDeferred<Unit> = CompletableDeferred()
  private var originMark = TimeSource.Monotonic.markNow()

  @Volatile
  var capturing: Boolean = false
    private set

  fun nowMs(): Double = originMark.elapsedNow().inWholeNanoseconds / 1_000_000.0

  fun reset() {
    pointer.clear()
    map.clear()
    origin = null
    capturing = false
    originMark = TimeSource.Monotonic.markNow()
    if (!drag.isCompleted) drag.cancel()
    drag = CompletableDeferred()
  }

  fun arm() {
    reset()
    capturing = true
  }

  fun onPointer(xPx: Double, yPx: Double, pressed: Boolean) {
    if (!capturing) return
    val tMs = nowMs()
    if (pressed) {
      if (origin == null) origin = TimedPoint(tMs, xPx, yPx)
      pointer.add(TimedPoint(tMs, xPx, yPx))
      return
    }
    val start = origin ?: return
    origin = null
    val distance = hypot(xPx - start.xPx, yPx - start.yPx)
    val durationMs = tMs - start.tMs
    if (distance >= MinimumDragPx && durationMs >= MinimumDragMs && !drag.isCompleted) {
      capturing = false
      drag.complete(Unit)
    }
  }

  fun onMapProjection(xPx: Double, yPx: Double) {
    if (!capturing && drag.isCompleted) return
    if (origin == null && pointer.isEmpty()) return
    map.add(TimedPoint(nowMs(), xPx, yPx))
  }

  suspend fun awaitQualifyingDrag() {
    drag.await()
  }

  fun stats(): GestureLatencyStats = estimateGestureLatency(pointer.toList(), map.toList())

  private companion object {
    const val MinimumDragPx = 80.0
    const val MinimumDragMs = 150.0
  }
}
