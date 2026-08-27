package org.maplibre.compose.map

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import org.maplibre.compose.camera.CameraMoveReason

/**
 * Deduplicates camera-move reports: one start each time the reason changes, one end once any start
 * was reported. Thread-agnostic; each engine calls it under its own single-threaded discipline.
 */
internal class CameraMoveReporter(
  private val moveReason: () -> CameraMoveReason,
  private val onStarted: (CameraMoveReason) -> Unit,
  private val onEnded: () -> Unit,
) {
  private var reported: CameraMoveReason? = null

  /** The reason is re-read every begin: a gesture flag can arrive after the first camera change. */
  fun begin() {
    val reason = moveReason()
    if (reported == reason) return
    reported = reason
    onStarted(reason)
  }

  fun end() {
    if (reported == null) return
    reported = null
    onEnded()
  }
}

/** Resumes every waiter normally: whatever release event they awaited will never arrive. */
internal fun resumeStranded(waiters: List<CancellableContinuation<Unit>>) {
  waiters.forEach { waiter -> if (waiter.isActive) runCatching { waiter.resume(Unit) } }
}
