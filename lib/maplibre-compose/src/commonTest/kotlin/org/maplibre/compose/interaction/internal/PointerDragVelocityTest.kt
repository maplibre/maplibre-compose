package org.maplibre.compose.interaction.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.HistoricalChange
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.Velocity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PointerDragVelocityTest {
  @Test
  fun departing_motion_cannot_fling_regardless_of_event_rate() {
    for (interval in listOf(4L, 8L, 16L)) {
      val tracker = PointerDragVelocity(Float.MAX_VALUE)
      tracker.begin(sample(0, 0f), afterContactChange = true)
      tracker.recognize(sample(interval, 100f))
      for (time in interval..96L step interval) {
        tracker.addPointerInputChange(sample(time, time * 10f))
      }
      assertEquals(Velocity.Zero, tracker.calculateVelocity(), "interval $interval")
    }
  }

  @Test
  fun continued_drag_uses_only_motion_after_the_contact_release_interval() {
    val tracker = PointerDragVelocity(Float.MAX_VALUE)
    tracker.begin(sample(0, 0f), afterContactChange = true)
    tracker.recognize(sample(8, 100f))
    val clean = GestureVelocityTracker()
    for (time in listOf(100L, 116L, 132L, 148L)) {
      val position = (time - 100) * 0.5f
      tracker.addPointerInputChange(
        sample(time, position)
          .copy(
            pressure = 1f,
            historical = listOf(HistoricalChange(96, Offset(-1000f, 0f))),
          )
      )
      clean.addPosition(time, Offset(position, 0f))
    }
    assertTrue(clean.calculateVelocity().x > 0f)
    assertEquals(clean.calculateVelocity(), tracker.calculateVelocity())
  }

  @Test
  fun a_fresh_press_has_no_contact_release_delay() {
    val tracker = PointerDragVelocity(Float.MAX_VALUE)
    tracker.begin(sample(0, 0f), afterContactChange = true)
    tracker.begin(sample(20, 0f))
    tracker.addPointerInputChange(sample(36, 10f))
    tracker.addPointerInputChange(sample(52, 20f))
    assertTrue(tracker.calculateVelocity().x > 0f)
  }

  private fun sample(time: Long, x: Float) =
    PointerInputChange(
      id = PointerId(1),
      uptimeMillis = time,
      position = Offset(x, 0f),
      pressed = true,
      previousUptimeMillis = time,
      previousPosition = Offset(x, 0f),
      previousPressed = true,
      isInitiallyConsumed = false,
    )
}
