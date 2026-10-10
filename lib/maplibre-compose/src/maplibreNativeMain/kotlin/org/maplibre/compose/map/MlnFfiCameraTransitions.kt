package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.CommandFailedException
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.failed
import org.maplibre.compose.mlnffi.reportFailure
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.nativeffi.generated.AnimationOptions
import org.maplibre.nativeffi.generated.CameraTransitionEnd
import org.maplibre.nativeffi.generated.CameraTransitionHandler
import org.maplibre.nativeffi.generated.CameraTransitionOutcome
import org.maplibre.nativeffi.generated.MapHandle
import org.maplibre.nativeffi.runtime.CommandCompletion

/**
 * Starts camera transitions that resume their caller when native reports their end, and keeps the
 * anchor policy: a viewport resize or padding change cancels the anchored transition. Cancelling a
 * caller only withdraws it; the camera keeps moving, as on every platform.
 *
 * [observe] runs before a transition's caller resumes, so camera getters show where it ended.
 */
internal class MlnFfiCameraTransitions(
  private val logger: () -> MapLog?,
  private val observe: (MapHandle) -> Unit,
) {
  /** A camera field that a transition animates. */
  enum class Field {
    Center,
    Zoom,
    Bearing,
    Pitch,
    Padding,
  }

  private class Anchor(val transition: Transition, val size: DpSize, val fields: Set<Field>)

  /**
   * One camera command. Its caller resumes once native has reported both the command's completion
   * and the end of its transitions, so a command that fails before it starts them reports why.
   */
  private inner class Transition(
    val id: ULong,
    val map: MapHandle,
    val continuation: CancellableContinuation<Unit>,
  ) {
    private var failure: Throwable? = null
    private var completed = false
    private var ended = false

    fun commandCompleted(error: Throwable?) = settle {
      completed = true
      failure = error
    }

    fun transitionEnded(end: CameraTransitionEnd) {
      lock.withLock { if (anchor?.transition === this) anchor = null }
      // The snapshot of the end's generation shows the final camera, so getters read after the
      // caller resumes show it. A closed map has nothing left to show.
      if (end.outcome != CameraTransitionOutcome.CLOSED) runCatching { observe(map) }
      settle { ended = true }
    }

    private fun settle(update: () -> Unit) {
      val (resume, error) =
        lock.withLock {
          val wasDone = completed && ended
          update()
          (!wasDone && completed && ended) to failure
        }
      if (!resume || !continuation.isActive) return
      if (error != null) continuation.resumeWithException(error) else continuation.resume(Unit)
    }
  }

  private val lock = MlnFfiLock()
  private var nextId = 0uL
  private var anchor: Anchor? = null

  fun start(
    map: MapHandle,
    animation: AnimationOptions,
    continuation: CancellableContinuation<Unit>,
    anchored: Boolean,
    /**
     * The fields the transition animates. An anchor couples center with every field of its update,
     * so a later transition of any of them stops the anchored one.
     */
    fields: Set<Field> = Field.entries.toSet(),
    start: (MapHandle, AnimationOptions) -> Deferred<CommandCompletion>,
  ) {
    // Cancellation while this waits for the map must not start a native transition.
    if (!continuation.isActive) return
    val size =
      if (anchored)
        map.getSnapshot().logicalExtent.let { DpSize(it.width.toInt().dp, it.height.toInt().dp) }
      else null
    val transition = Transition(lock.withLock { ++nextId }, map, continuation)
    val started =
      try {
        start(
          map,
          animation.copy(
            transitionId = transition.id,
            endHandler = CameraTransitionHandler(transition::transitionEnded),
          ),
        )
      } catch (error: Throwable) {
        // A rejected command never runs its end handler.
        if (continuation.isActive) continuation.resumeWithException(error)
        return
      }
    started.invokeOnCompletion { error ->
      @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
      val failure =
        error ?: started.getCompleted().takeIf { it.failed }?.let(::CommandFailedException)
      transition.commandCompleted(failure)
    }
    lock.withLock {
      // Native stops a replaced anchor. Geometry changes from here on no longer belong to it, so
      // they must not cancel the replacement.
      if (anchor?.fields?.any { it in fields } == true) anchor = null
      if (size != null) anchor = Anchor(transition, size, fields + Field.Center)
    }
  }

  fun viewportChanged(size: DpSize) {
    if (lock.withLock { anchor?.let { it.size != size } == true }) cancelAnchor()
  }

  /** Geometry invalidates the anchor, so its transition stops where it stands. */
  fun cancelAnchor() {
    val current = lock.withLock { anchor.also { anchor = null } } ?: return
    current.transition.continuation.cancel(CancellationException("The anchor viewport changed"))
    current.transition.map
      .cancelCameraTransition(current.transition.id)
      .reportFailure(logger(), "cancel the anchored camera transition")
  }
}
