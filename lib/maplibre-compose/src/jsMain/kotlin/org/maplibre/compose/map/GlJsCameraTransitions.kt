package org.maplibre.compose.map

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.isCameraEasing

/**
 * Main-thread movement completion for GL JS. It has no transition IDs: moveend releases the
 * movement being replaced as well as one that reaches its destination. Only one movement can wait
 * for the initial style. Admission and takeover remain with CameraInputAuthority.
 */
internal class GlJsCameraTransitions {
  private class Movement(
    val continuation: CancellableContinuation<Unit>,
    val anchored: Boolean,
    val start: (MaplibreMap) -> Boolean,
  ) {
    fun release() {
      if (continuation.isActive) continuation.resume(Unit)
    }
  }

  private var pending: Movement? = null
  private val waiters = mutableListOf<Movement>()
  private var anchor: Movement? = null

  /** A null map means the engine or its first style is not ready yet. */
  fun start(
    map: MaplibreMap?,
    continuation: CancellableContinuation<Unit>,
    anchored: Boolean,
    command: (MaplibreMap) -> Boolean,
  ) {
    if (!continuation.isActive) return
    val movement = Movement(continuation, anchored, command)
    continuation.invokeOnCancellation {
      if (pending === movement) pending = null
      waiters.remove(movement)
      // The engine keeps moving. Its anchor still needs invalidating on a viewport change.
    }
    if (map == null) {
      val previous = pending
      pending = movement
      previous?.release()
    } else start(map, movement)
  }

  fun initialStyleLoaded(map: MaplibreMap) {
    val movement = pending ?: return
    pending = null
    start(map, movement)
  }

  fun releasePending() {
    val movement = pending
    pending = null
    movement?.release()
  }

  private fun start(map: MaplibreMap, movement: Movement) {
    if (!movement.continuation.isActive) return
    try {
      if (!movement.start(map)) {
        movement.release()
        return
      }
    } catch (error: Throwable) {
      if (movement.continuation.isActive) movement.continuation.resumeWith(Result.failure(error))
      return
    }
    // Register after start: its synchronous moveend ends the preceding movement. An immediate
    // movement also emits moveend here, but leaves no easing to wait for when start returns.
    if (map.isCameraEasing()) {
      if (movement.continuation.isActive) waiters += movement
      if (movement.anchored) anchor = movement
    } else movement.release()
  }

  /** Retire before callbacks can start another movement; publish the camera before resuming. */
  fun movementEnded(observe: () -> Unit) {
    val resuming = retire()
    try {
      observe()
    } finally {
      resuming.forEach { it.release() }
    }
  }

  fun cancelAnchor(map: MaplibreMap?) {
    val movement = anchor ?: return
    anchor = null
    movement.continuation.cancel(CancellationException("The anchor viewport changed"))
    map?.stop()
  }

  /** A destroyed engine emits no moveend; pending work can wait for its replacement's style. */
  fun engineDestroyed() {
    retire().forEach { it.release() }
  }

  fun releaseAll() {
    releasePending()
    engineDestroyed()
  }

  private fun retire(): List<Movement> {
    anchor = null
    return waiters.toList().also { waiters.clear() }
  }
}
