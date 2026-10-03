package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.nativeffi.camera.AnimationOptions
import org.maplibre.nativeffi.map.MapHandle

/**
 * Native ID completion. Commands/events use the map owner; [releaseAll] may run during failed
 * renderer teardown. The lock protects state; engine calls and continuations run outside it.
 */
internal class MlnFfiCameraTransitions(private val logger: () -> MapLog?) {
  private class Anchor(val id: Long, val size: DpSize)

  private val lock = MlnFfiLock()
  private var retired = false
  private var nextId = 0L
  private var anchor: Anchor? = null
  private val waiters = mutableMapOf<Long, CancellableContinuation<Unit>>()
  private val pendingResumes = mutableListOf<CancellableContinuation<Unit>>()

  fun start(
    map: MapHandle,
    animation: AnimationOptions,
    continuation: CancellableContinuation<Unit>,
    anchored: Boolean,
    start: (MapHandle, AnimationOptions) -> Unit,
  ) {
    // Cancellation while this waits for the map must not start a native transition.
    if (!continuation.isActive) return
    val size = if (anchored) map.size.let { DpSize(it.width.dp, it.height.dp) } else null
    val id = lock.withLock {
      if (retired) return@withLock null
      val id = ++nextId
      waiters[id] = continuation
      id
    }
    if (id == null) {
      continuation.resume(Unit)
      return
    }
    continuation.invokeOnCancellation {
      // Withdrawing a waiter cannot safely stop its tracks without scoped FFI cancellation.
      lock.withLock { waiters.remove(id) }
    }
    try {
      start(map, animation.copy { transitionId = id })
    } catch (error: Throwable) {
      // A rejected command emits no event, so nothing else would resume the continuation.
      lock.withLock { waiters.remove(id) }
      if (continuation.isActive) continuation.resumeWithException(error)
      return
    }
    // Native drains finish events after this command; a rejected start keeps the old anchor.
    if (size != null) lock.withLock { if (!retired) anchor = Anchor(id, size) }
  }

  fun finished(id: Long) {
    val known = lock.withLock {
      if (anchor?.id == id) anchor = null
      val waiter = waiters.remove(id)
      if (waiter != null) pendingResumes += waiter
      waiter != null
    }
    // Native queues this before MAP_CAMERA_DID_CHANGE. Resume only after the final observation.
    if (!known) logger()?.d { "Ignoring the end of unknown camera transition $id" }
  }

  fun viewportChanged(map: MapHandle, size: DpSize) {
    if (lock.withLock { anchor?.let { it.size != size } == true }) cancelAnchor(map)
  }

  /** Geometry invalidates the anchor. Without scoped cancellation this stops every track. */
  fun cancelAnchor(map: MapHandle) {
    val waiter = lock.withLock {
      val current = anchor ?: return
      anchor = null
      waiters[current.id]
    }
    waiter?.cancel(CancellationException("The anchor viewport changed"))
    map.cancelTransitions()
  }

  fun eventsDrained() {
    val resuming = lock.withLock { pendingResumes.toList().also { pendingResumes.clear() } }
    resume(resuming)
  }

  /** Closing a map discards its queued events, so no finish event will follow. */
  fun releaseAll() {
    val stranded = lock.withLock {
      retired = true
      anchor = null
      (waiters.values + pendingResumes).also {
        waiters.clear()
        pendingResumes.clear()
      }
    }
    resume(stranded)
  }

  private fun resume(continuations: List<CancellableContinuation<Unit>>) {
    continuations.forEach { waiter -> runCatching { waiter.resume(Unit) } }
  }
}
