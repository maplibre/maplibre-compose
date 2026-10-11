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
import org.maplibre.compose.mlnffi.failed
import org.maplibre.compose.mlnffi.reportFailure
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
 * Native callbacks post to main. An inline drain delivers the final camera events before the caller
 * resumes, so camera state shows where the transition ended.
 */
internal class MlnFfiCameraTransitions(
  private val logger: () -> MapLog?,
  private val postToMain: (action: () -> Unit) -> Unit,
  private val drain: () -> Unit,
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
      if (anchor?.transition === this) anchor = null
      // Native queues the events of the change that ended the transition before this runs. A
      // closed map delivers no more events.
      if (end.outcome == CameraTransitionOutcome.CLOSED) settle { ended = true }
      else {
        drain()
        settle { ended = true }
      }
    }

    private fun settle(update: () -> Unit) {
      val wasDone = completed && ended
      update()
      val resume = !wasDone && completed && ended
      val error = failure
      if (!resume || !continuation.isActive) return
      if (error != null) continuation.resumeWithException(error) else continuation.resume(Unit)
    }
  }

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
    val transition = Transition(++nextId, map, continuation)
    val started =
      try {
        start(
          map,
          animation.copy(
            transitionId = transition.id,
            endHandler =
              CameraTransitionHandler { end ->
                postToMain { transition.transitionEnded(end) }
              },
          ),
        )
      } catch (error: Throwable) {
        // A rejected command never runs its end handler.
        if (continuation.isActive) continuation.resumeWithException(error)
        return
      }
    started.invokeOnCompletion { error ->
      postToMain {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val failure =
          error ?: started.getCompleted().takeIf { it.failed }?.let(::CommandFailedException)
        transition.commandCompleted(failure)
      }
    }
    // Native stops a replaced anchor. Geometry changes from here on no longer belong to it, so
    // they must not cancel the replacement.
    if (anchor?.fields?.any { it in fields } == true) anchor = null
    if (size != null) anchor = Anchor(transition, size, fields + Field.Center)
  }

  fun viewportChanged(size: DpSize) {
    if (anchor?.let { it.size != size } == true) cancelAnchor()
  }

  /** Geometry invalidates the anchor, so its transition stops where it stands. */
  fun cancelAnchor() {
    val current = anchor.also { anchor = null } ?: return
    current.transition.continuation.cancel(CancellationException("The anchor viewport changed"))
    current.transition.map
      .cancelCameraTransition(current.transition.id)
      .reportFailure(logger(), "cancel the anchored camera transition")
  }
}
