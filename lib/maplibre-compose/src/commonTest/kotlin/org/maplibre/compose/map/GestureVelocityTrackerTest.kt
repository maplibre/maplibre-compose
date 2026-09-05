package org.maplibre.compose.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import kotlin.test.Test
import kotlin.test.assertEquals

class GestureVelocityTrackerTest {
  @Test
  fun equal_time_motion_has_no_invented_velocity() {
    val tracker = GestureVelocityTracker()
    tracker.addPosition(10, Offset.Zero)
    tracker.addPosition(10, Offset(10f, 20f))
    tracker.addPosition(10, Offset(30f, 50f))
    assertEquals(Velocity.Zero, tracker.calculateVelocity())
  }

  @Test
  fun equal_time_samples_coalesce_before_the_next_distinct_sample() {
    val tracker = GestureVelocityTracker()
    tracker.addPosition(10, Offset.Zero)
    tracker.addPosition(10, Offset(10f, 20f))
    tracker.addPosition(20, Offset(20f, 40f))
    assertEquals(Velocity(1000f, 2000f), tracker.calculateVelocity())
  }

  @Test
  fun backwards_time_rebases_velocity_history() {
    val tracker = GestureVelocityTracker()
    tracker.addPosition(10, Offset.Zero)
    tracker.addPosition(20, Offset(10f, 20f))
    tracker.addPosition(15, Offset(20f, 40f))
    assertEquals(Velocity.Zero, tracker.calculateVelocity())
    tracker.addPosition(30, Offset(30f, 60f))
    assertEquals(Velocity.Zero, tracker.calculateVelocity())
  }
}
