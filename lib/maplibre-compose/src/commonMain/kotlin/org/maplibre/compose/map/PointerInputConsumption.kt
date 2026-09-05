package org.maplibre.compose.map

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId

/** Main/Final cooperation for the contact group owned by one map input arena. */
internal class PointerInputConsumption(private val cancel: () -> Unit) {
  private val contacts = mutableSetOf<PointerId>()
  private var suppressed = false
  private var relevant = emptySet<PointerId>()
  private var consumedHere = emptySet<PointerId>()

  fun main(event: PointerEvent, recognize: (PointerEvent) -> Unit) {
    val pressed = event.changes.filter { it.pressed }.mapTo(mutableSetOf()) { it.id }
    relevant = contacts + event.changes.filter { it.pressed && !it.previousPressed }.map { it.id }
    consumedHere = emptySet()

    // A restarted pointerInput can first see a Move. It must wait for that group to lift.
    val orphaned = event.changes.any { it.previousPressed && it.id !in contacts }
    val intercepted = event.changes.any { it.id in relevant && it.isConsumed }
    val hadContacts = contacts.isNotEmpty()
    contacts.clear()
    contacts.addAll(pressed)
    if (suppressed || orphaned || intercepted) {
      if (!suppressed && hadContacts) cancel()
      suppressed = pressed.isNotEmpty()
      relevant = emptySet()
      return
    }

    recognize(event)
    consumedHere =
      event.changes.filter { it.id in relevant && it.isConsumed }.mapTo(mutableSetOf()) { it.id }
  }

  fun final(event: PointerEvent) {
    if (event.changes.any { it.id in relevant && it.id !in consumedHere && it.isConsumed }) {
      cancel()
      suppressed = contacts.isNotEmpty()
    }
    relevant = emptySet()
    consumedHere = emptySet()
  }

  /** Independent input can take over camera ownership without letting a held contact restart. */
  fun suppress() {
    cancel()
    suppressed = contacts.isNotEmpty()
    relevant = emptySet()
  }
}
