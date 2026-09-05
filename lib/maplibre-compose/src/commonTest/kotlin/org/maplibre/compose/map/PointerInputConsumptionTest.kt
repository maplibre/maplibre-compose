package org.maplibre.compose.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.input.PointerInputConsumption

class PointerInputConsumptionTest {
  @Test
  fun restarting_during_a_drag_waits_for_release_before_accepting_a_new_press() {
    var recognized = 0
    val arena = PointerInputConsumption { error("a restarted arena has no action to cancel") }
    arena.main(event(change(pressed = true, previousPressed = true))) { recognized++ }
    arena.main(event(change(pressed = false, previousPressed = true))) { recognized++ }
    assertEquals(0, recognized)
    arena.main(event(change(pressed = true, previousPressed = false))) { recognized++ }
    assertEquals(1, recognized)
  }

  @Test
  fun an_intercepted_new_press_does_not_interrupt_an_unrelated_camera_continuation() {
    var recognized = 0
    val arena = PointerInputConsumption { error("an intercepted down took over input") }
    val down = change(pressed = true, previousPressed = false).also { it.consume() }
    arena.main(event(down)) { recognized++ }
    arena.main(event(change(pressed = false, previousPressed = true))) { recognized++ }
    assertEquals(0, recognized)
  }

  @Test
  fun final_consumption_cancels_the_pair_and_suppresses_new_contacts_until_the_group_lifts() {
    var recognized = 0
    var cancelled = 0
    val arena = PointerInputConsumption { cancelled++ }
    arena.main(event(change(true, false, 1), change(true, false, 2))) { recognized++ }
    val move = event(change(true, true, 1), change(true, true, 2))
    arena.main(move) { recognized++ }
    move.changes.first().consume()
    arena.final(move)
    assertEquals(1, cancelled)
    arena.main(event(change(false, true, 1), change(true, true, 2))) { recognized++ }
    arena.main(event(change(true, true, 2), change(true, false, 3))) { recognized++ }
    arena.main(event(change(false, true, 2), change(true, true, 3))) { recognized++ }
    arena.main(event(change(false, true, 3))) { recognized++ }
    assertEquals(2, recognized)
    assertEquals(1, cancelled)
    arena.main(event(change(true, false, 4))) { recognized++ }
    assertEquals(3, recognized)
  }

  @Test
  fun consumed_hover_does_not_cancel_contact_recognition() {
    var recognized = 0
    val arena = PointerInputConsumption { error("hover consumption cancelled contact input") }
    val hover = event(change(false, false).also { it.consume() })
    arena.main(hover) { recognized++ }
    arena.final(hover)
    arena.main(event(change(true, false))) { recognized++ }
    assertEquals(2, recognized)
  }

  private fun event(vararg changes: PointerInputChange): PointerEvent =
    PointerEvent(changes.toList())

  private fun change(pressed: Boolean, previousPressed: Boolean, id: Long = 1): PointerInputChange =
    PointerInputChange(
      id = PointerId(id),
      uptimeMillis = 16,
      position = Offset(10f, 10f),
      pressed = pressed,
      previousUptimeMillis = 0,
      previousPosition = Offset.Zero,
      previousPressed = previousPressed,
      isInitiallyConsumed = false,
    )
}
