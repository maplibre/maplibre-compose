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
 * transition ended before the caller resumes.
 */
internal class MlnFfiCameraTransitions(private val postToMain: (action: () -> Unit) -> Unit) {
  /** A camera field that a transition animates. */
  enum class Field {
    Center,
    Zoom,
    Bearing,
    Pitch,
    Padding,
  }

  private class Anchor(val transition: Transition, val size: DpSize, val fields: Set<Field>)

  private inner class Transition(
    val id: ULong,
    val map: MapHandle,
    val continuation: CancellableContinuation<Unit>,
  ) {
    fun transitionEnded() {
      if (anchor?.transition === this) anchor = null
      if (continuation.isActive) continuation.resume(Unit)
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
    start: (MapHandle, AnimationOptions) -> Unit,
  ) {
    // Cancellation while this waits for the map must not start a native transition.
    if (!continuation.isActive) return
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
      return
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
    current.transition.map.discarding.cancelCameraTransition(current.transition.id)
  }
}
