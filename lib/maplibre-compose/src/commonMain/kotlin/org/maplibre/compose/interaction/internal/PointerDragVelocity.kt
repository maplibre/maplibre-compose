package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.Velocity

/** Separates direct dragging from the motion eligible to produce release momentum. */
internal class PointerDragVelocity(maximumVelocity: Float) {
  private val tracker = GestureVelocityTracker(maximumVelocity)
  private var notBefore = Long.MIN_VALUE

  fun begin(change: PointerInputChange, afterContactChange: Boolean = false) {
    resetTracking()
    if (afterContactChange) notBefore = change.uptimeMillis + CONTACT_RELEASE_MILLIS
    addPointerInputChange(change)
  }

  fun recognize(change: PointerInputChange) {
    tracker.resetTracking()
    if (change.uptimeMillis >= notBefore) tracker.addPosition(change.uptimeMillis, change.position)
  }

  fun addPointerInputChange(change: PointerInputChange) {
    // Historical samples must obey the same eligibility boundary as current samples.
    tracker.addPointerInputChange(change, notBefore)
  }

  fun calculateVelocity(): Velocity = tracker.calculateVelocity()

  fun resetTracking() {
    tracker.resetTracking()
    notBefore = Long.MIN_VALUE
  }

  private companion object {
    // Brief movement between finger lifts belongs to releasing the old contact group.
    const val CONTACT_RELEASE_MILLIS = 100L
  }
}
