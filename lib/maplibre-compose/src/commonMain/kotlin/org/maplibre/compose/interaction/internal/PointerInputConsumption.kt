package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId

/** Main/Final cooperation for the contact group owned by one input handler. */
internal class PointerInputConsumption(private val cancel: () -> Unit) {
  private val contacts = mutableSetOf<PointerId>()
  private var suppressed = false
  private var relevant = emptySet<PointerId>()
  private var consumedHere = emptySet<PointerId>()

  fun main(event: PointerEvent, enabled: Boolean, recognize: (PointerEvent) -> Unit): Boolean {
    val pressed = event.changes.filter { it.pressed }.mapTo(mutableSetOf()) { it.id }
    relevant = contacts + event.changes.filter { it.pressed && !it.previousPressed }.map { it.id }
    consumedHere = emptySet()

    // A restarted pointerInput can first see a Move. It must wait for that group to lift.
    val orphaned = event.changes.any { it.previousPressed && it.id !in contacts }
    val intercepted = event.changes.any { it.id in relevant && it.isConsumed }
    val hadContacts = contacts.isNotEmpty()
    contacts.clear()
    contacts.addAll(pressed)
    if (!enabled || suppressed || orphaned || intercepted) {
      if (!suppressed && hadContacts) cancel()
      suppressed = pressed.isNotEmpty()
      relevant = emptySet()
      return false
    }

    recognize(event)
    consumedHere =
      event.changes.filter { it.id in relevant && it.isConsumed }.mapTo(mutableSetOf()) { it.id }
    return true
  }

  fun final(event: PointerEvent) {
    if (event.changes.any { it.id in relevant && it.id !in consumedHere && it.isConsumed }) {
      cancel()
      suppressed = contacts.isNotEmpty()
    }
    relevant = emptySet()
    consumedHere = emptySet()
  }

  /** Independent input can take over input ownership without letting a held contact restart. */
  fun suppress() {
    cancel()
    suppressed = contacts.isNotEmpty()
    relevant = emptySet()
  }
}
