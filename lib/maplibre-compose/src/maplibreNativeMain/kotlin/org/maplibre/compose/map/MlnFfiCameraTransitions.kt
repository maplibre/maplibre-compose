package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import org.maplibre.nativeffi.AnimationOptions
import org.maplibre.nativeffi.CameraTransitionHandler
import org.maplibre.nativeffi.MapHandle

/**
 * Starts camera transitions that resume their caller when native reports their end, and keeps the
 * anchor policy: a viewport resize or padding change cancels the anchored transition. Cancelling a
 * caller only withdraws it; the camera keeps moving, as on every platform.
 *
 * Native callbacks post to main after the final camera events, so camera state shows where the
 * transition ended before the caller resumes. Those events can reach main a task before the end,
 * and the caller resumes in a task after it, so a transition keeps [isMoving] true until its caller
 * has resumed, or until its end when the caller left first.
 */
internal class MlnFfiCameraTransitions(
  private val postToMain: (action: () -> Unit) -> Unit,
  /** Runs on main when [isMoving] turns false. */
  private val onRest: () -> Unit,
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

  /** A started transition, which its caller [leaves][callerLeft] once it stops waiting. */
  inner class Transition(
    val id: ULong,
    val map: MapHandle,
    val continuation: CancellableContinuation<Unit>,
  ) {
    private var ended = false
    private var left = false

    fun transitionEnded() {
      if (anchor?.transition === this) anchor = null
      ended = true
      if (left) settle() else if (continuation.isActive) continuation.resume(Unit)
    }

    /** On main, once the caller resumes, however it resumes. */
    fun callerLeft() {
      left = true
      if (ended) settle()
    }

    private fun settle() {
      if (--moving == 0) onRest()
    }
  }

  private var nextId = 0uL
  private var anchor: Anchor? = null
  private var moving = 0

  /** Whether a transition started here hasn't settled, even if the camera already shows its end. */
  val isMoving: Boolean
    get() = moving > 0

  /**
   * A transition ID that no transition started here uses, for a camera change started elsewhere.
   */
  fun reserveId(): ULong = ++nextId

  /** Returns the started transition, or null if it didn't start. */
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
    start: (MapHandle, AnimationOptions) -> Unit,
  ): Transition? {
    // Cancellation while this waits for the map must not start a native transition.
    if (!continuation.isActive) return null
    val size =
      if (anchored)
        map.getSnapshot().view.extent.let { DpSize(it.width.toInt().dp, it.height.toInt().dp) }
      else null
    val transition = Transition(++nextId, map, continuation)
    try {
      start(
        map,
        animation.copy(
          transitionId = transition.id,
          endHandler =
            CameraTransitionHandler {
              postToMain { transition.transitionEnded() }
            },
        ),
      )
    } catch (error: Throwable) {
      // A rejected command never runs its end handler.
      if (continuation.isActive) continuation.resumeWithException(error)
      return null
    }
    moving++
    // Native stops a replaced anchor. Geometry changes from here on no longer belong to it, so
    // they must not cancel the replacement.
    if (anchor?.fields?.any { it in fields } == true) anchor = null
    if (size != null) anchor = Anchor(transition, size, fields + Field.Center)
    return transition
  }

  fun viewportChanged(size: DpSize) {
    if (anchor?.let { it.size != size } == true) cancelAnchor()
  }

  /** Geometry invalidates the anchor, so its transition stops where it stands. */
  fun cancelAnchor() {
    val current = anchor.also { anchor = null } ?: return
    current.transition.continuation.cancel(CancellationException("The anchor viewport changed"))
    current.transition.map.discarding.cancelCameraTransitionsWithId(current.transition.id)
  }
}
