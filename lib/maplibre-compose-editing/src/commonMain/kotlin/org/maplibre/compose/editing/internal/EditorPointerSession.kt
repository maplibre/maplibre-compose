package org.maplibre.compose.editing.internal

import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
import org.maplibre.compose.editing.EditStep
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.PointerButton

/** One pointer change in dp, as [EditorPointerSession] reads it. */
internal data class PointerSample(
  val id: Long,
  val pressed: Boolean,
  val previousPressed: Boolean,
  val screen: DpOffset,
  val timeMillis: Long,
  val pointerType: PointerType,
  val buttons: Set<PointerButton>,
  val modifierKeys: Set<KeyModifier>,
)

/** Receives the gestures [EditorPointerSession] recognizes. */
internal interface EditorPointerHandler {
  /** Returns true to claim the pointer. */
  fun onPress(sample: PointerSample, step: EditStep): Boolean

  /** Returns false to cancel the gesture. */
  fun onDrag(sample: PointerSample, step: EditStep): Boolean

  fun onRelease(sample: PointerSample, step: EditStep)

  /** Returns true to consume the tap. */
  fun onTap(sample: PointerSample, count: Int, step: EditStep): Boolean

  fun onLongPress(sample: PointerSample, step: EditStep)

  fun onCancel(step: EditStep)
}

/**
 * Recognizes editor gestures from pointer samples.
 *
 * The first pointer of a contact group is tracked. A claimed pointer's changes are all consumed; a
 * cancelled or committed group is swallowed until every contact lifts. [onEvent] returns the ids of
 * the changes to consume.
 */
internal class EditorPointerSession(
  private val handler: EditorPointerHandler,
  private val touchSlop: () -> Dp,
  private val doubleTapTimeoutMillis: () -> Long,
  private val doubleTapRadius: (PointerType) -> Dp,
) {
  private class Tracked(
    val id: Long,
    val origin: PointerSample,
    val step: EditStep,
    val claimed: Boolean,
  ) {
    var dragged = false
    var tapEligible = true
    var last = origin
  }

  private class TapRecord(val timeMillis: Long, val screen: DpOffset, val type: PointerType)

  private val contacts = mutableSetOf<Long>()
  private var tracked: Tracked? = null
  private var swallowed = false
  private var lastTap: TapRecord? = null

  /** Whether a claimed pointer is down and its gesture has not ended. */
  val isClaimed: Boolean
    get() = !swallowed && tracked?.claimed == true

  /** Whether a claimed touch or stylus pointer is held within slop. */
  val longPressPending: Boolean
    get() {
      val t = tracked ?: return false
      return !swallowed &&
        t.claimed &&
        !t.dragged &&
        (t.origin.pointerType == PointerType.Touch || t.origin.pointerType == PointerType.Stylus)
    }

  fun onEvent(samples: List<PointerSample>): Set<Long> {
    val consume = mutableSetOf<Long>()
    for (sample in samples) {
      when {
        sample.pressed && !sample.previousPressed -> onPress(sample, consume)
        sample.pressed && sample.previousPressed -> onMove(sample, consume)
        !sample.pressed && sample.previousPressed -> onRelease(sample, consume)
      }
    }
    return consume
  }

  /** Delivers a long press when one is pending. Returns whether it did. */
  fun longPress(): Boolean {
    if (!longPressPending) return false
    val t = checkNotNull(tracked)
    handler.onLongPress(t.last, t.step)
    swallow()
    return true
  }

  /** Cancels a claimed gesture. Returns whether one was cancelled. */
  fun cancel(): Boolean {
    val t = tracked
    if (swallowed || t == null || !t.claimed) return false
    handler.onCancel(t.step)
    swallow()
    return true
  }

  /** Cancels a claimed gesture and forgets every contact. */
  fun reset() {
    cancel()
    contacts.clear()
    tracked = null
    swallowed = false
  }

  private fun onPress(sample: PointerSample, consume: MutableSet<Long>) {
    val wasEmpty = contacts.isEmpty()
    contacts += sample.id
    if (swallowed) {
      consume += sample.id
      return
    }
    val t = tracked
    if (t == null) {
      if (!wasEmpty) return
      val step = EditStep()
      val claimed = handler.onPress(sample, step)
      tracked = Tracked(sample.id, sample, step, claimed)
      if (claimed) consume += sample.id
    } else if (t.claimed) {
      if (t.dragged) handler.onRelease(t.last, t.step) else handler.onCancel(t.step)
      swallow()
      consume += sample.id
    } else {
      t.tapEligible = false
    }
  }

  private fun onMove(sample: PointerSample, consume: MutableSet<Long>) {
    if (swallowed) {
      consume += sample.id
      return
    }
    val t = tracked ?: return
    if (sample.id != t.id) return
    val pastSlop = distance(sample.screen, t.origin.screen) > slop(t.origin.pointerType)
    if (t.claimed) {
      consume += sample.id
      t.last = sample
      if (pastSlop) t.dragged = true
      if (t.dragged && !handler.onDrag(sample, t.step)) {
        handler.onCancel(t.step)
        swallow()
      }
    } else if (pastSlop) {
      t.tapEligible = false
    }
  }

  private fun onRelease(sample: PointerSample, consume: MutableSet<Long>) {
    contacts -= sample.id
    val t = tracked
    if (swallowed) {
      consume += sample.id
    } else if (t != null && sample.id == t.id) {
      tracked = null
      if (t.claimed) consume += sample.id
      if (t.claimed && t.dragged) {
        handler.onRelease(sample, t.step)
      } else if (t.tapEligible) {
        val count = if (isSecondTap(t.origin)) 2 else 1
        val consumed = handler.onTap(sample, count, t.step)
        if (consumed) consume += sample.id
        lastTap =
          if (consumed && count == 1)
            TapRecord(sample.timeMillis, sample.screen, sample.pointerType)
          else null
      }
    }
    if (contacts.isEmpty()) {
      tracked = null
      swallowed = false
    }
  }

  private fun isSecondTap(press: PointerSample): Boolean {
    val previous = lastTap ?: return false
    return previous.type == press.pointerType &&
      press.timeMillis - previous.timeMillis <= doubleTapTimeoutMillis() &&
      distance(press.screen, previous.screen) <= doubleTapRadius(press.pointerType)
  }

  private fun swallow() {
    if (contacts.isEmpty()) {
      tracked = null
      swallowed = false
    } else {
      swallowed = true
    }
  }

  private fun slop(type: PointerType): Dp =
    if (type == PointerType.Mouse) MOUSE_SLOP else touchSlop()

  private fun distance(a: DpOffset, b: DpOffset): Dp {
    val dx = (a.x - b.x).value
    val dy = (a.y - b.y).value
    return sqrt(dx * dx + dy * dy).dp
  }

  private companion object {
    val MOUSE_SLOP = 4.dp
  }
}
