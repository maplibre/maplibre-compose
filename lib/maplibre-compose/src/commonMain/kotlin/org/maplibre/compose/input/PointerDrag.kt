package org.maplibre.compose.input

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlin.math.abs
import kotlin.math.sign

/**
 * A reserved contact's geometric lifetime. The caller decides which application action reserves it.
 */
internal class PointerDrag(
  first: PointerInputChange,
  private val slop: Float,
  private val verticalOnly: Boolean = false,
) {
  var origin = first.position
    private set

  private var previous = first
  private var closed = false
  var active = false
    private set

  data class Motion(
    val started: Boolean,
    val delta: Offset,
    val thresholdOffset: Offset = Offset.Zero,
  )

  fun rebase(change: PointerInputChange) {
    origin = change.position
    previous = change
  }

  fun move(change: PointerInputChange): Motion? {
    if (closed) return null
    val old = previous
    previous = change
    if (change.uptimeMillis < old.uptimeMillis) {
      rebase(change)
      return null
    }
    val delta = change.position - old.position
    if (delta == Offset.Zero) return null
    if (active) return Motion(false, delta)
    val displacement = change.position - origin
    val beyond =
      if (verticalOnly) {
        if (abs(displacement.y) < slop || displacement.y == 0f) return null
        Offset(0f, displacement.y - sign(displacement.y) * slop)
      } else {
        val distance = displacement.getDistance()
        if (distance < slop || distance == 0f) return null
        displacement * ((distance - slop) / distance)
      }
    active = true
    return Motion(true, beyond, displacement - beyond)
  }

  /**
   * End and cancellation both close recognition; application commit/rollback belongs to the caller.
   */
  fun finish(): Boolean {
    val started = active
    active = false
    closed = true
    return started
  }
}
