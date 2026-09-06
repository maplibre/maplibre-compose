package org.maplibre.compose.interaction.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.ui.unit.Velocity

/** Coalesces quantized host samples before passing distinct times to Compose's velocity fit. */
internal class GestureVelocityTracker(private val maximumVelocity: Float = Float.MAX_VALUE) {
  private data class Sample(val time: Long, val position: Offset)

  private val samples = mutableListOf<Sample>()

  fun resetTracking() = samples.clear()

  fun addPointerInputChange(change: PointerInputChange) {
    // A newly selected contact may carry history from its previous gesture.
    val lastTime = samples.lastOrNull()?.time
    change.historical.forEach {
      if (lastTime == null || it.uptimeMillis >= lastTime) addPosition(it.uptimeMillis, it.position)
    }
    addPosition(change.uptimeMillis, change.position)
  }

  fun addPosition(time: Long, position: Offset) {
    val previous = samples.lastOrNull()
    if (previous != null && time < previous.time) {
      samples.clear()
      return
    }
    if (previous?.time == time) samples.removeAt(samples.lastIndex)
    samples.add(Sample(time, position))
    while (samples.size > 20 || samples.size > 1 && time - samples.first().time > 100) {
      samples.removeAt(0)
    }
  }

  /** Pointer input uses the host's estimator; delta streams use the common axis estimators. */
  fun calculateVelocity(pointerInput: Boolean = true): Velocity {
    if (samples.size < 2) return Velocity.Zero
    if (!pointerInput) {
      val x = VelocityTracker1D(isDataDifferential = false)
      val y = VelocityTracker1D(isDataDifferential = false)
      samples.forEach {
        x.addDataPoint(it.time, it.position.x)
        y.addDataPoint(it.time, it.position.y)
      }
      return Velocity(x.calculateVelocity(), y.calculateVelocity())
    }
    val tracker = VelocityTracker()
    samples.forEach { tracker.addPosition(it.time, it.position) }
    return tracker.calculateVelocity(Velocity(maximumVelocity, maximumVelocity))
  }
}
