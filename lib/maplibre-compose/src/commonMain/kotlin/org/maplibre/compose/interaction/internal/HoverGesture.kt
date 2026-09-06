package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.maplibre.compose.interaction.HoverEvent
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.spatialk.geojson.Position

/** Observes pointer input without querying features or listening to map rendering. */
internal class HoverGesture(
  scope: CoroutineScope,
  private val project: (DpOffset) -> Position?,
  private val options: () -> MapInteractions,
  private val ids: GestureIds,
  private val density: Density,
  private val subscriptions: InteractionSubscriptions,
) {
  private data class Entered(
    val sample: GesturePointerSample,
    val subscription: Any?,
    val handler: (HoverEvent) -> Unit,
  )

  private var location: GesturePointerSample? = null
  private var entered: Entered? = null

  init {
    scope.launch {
      snapshotFlow {
        listOf(options().bindings.hover, options().callbacks.hover, subscriptions.hover.capture())
      }
        .collect { refresh(moved = false) }
    }
  }

  fun onPointerEvent(event: PointerEvent) {
    if (event.type == PointerEventType.Exit) {
      exit()
      return
    }
    // A captured drag can keep delivering moves outside the map after Exit.
    if (
      event.type != PointerEventType.Enter &&
        (event.type != PointerEventType.Move || location == null)
    )
      return
    val change = event.changes.firstOrNull { it.type in hoverTypes } ?: return
    val sample = event.gestureSample(0, null, density, change.position, setOf(change.type))
    move(sample)
  }

  internal fun move(sample: GesturePointerSample) {
    location = sample
    refresh(moved = true)
  }

  private fun refresh(moved: Boolean) {
    val raw = location ?: return
    val binding = options().bindings.hover
    val handler = options().callbacks.hover?.takeIf { binding.matches(raw) }
    val subscription = subscriptions.hover.capture()
    val previous = entered
    if (previous != null && (handler == null || previous.subscription !== subscription)) {
      leave()
      // Exit callbacks can change the subscription or send more input.
      if (location === raw) refresh(moved = false)
      return
    }
    if (handler == null) return
    if (previous != null && !moved) {
      entered = previous.copy(handler = handler)
      return
    }
    val sample =
      raw.copy(
        gestureId = previous?.sample?.gestureId ?: ids.next(),
        position = project(raw.screenOffset),
      )
    entered = Entered(sample, subscription, handler)
    handler(if (previous == null) HoverEvent.Enter(sample) else HoverEvent.Move(sample))
  }

  fun exit() {
    location = null
    leave()
  }

  private fun leave() {
    val previous = entered ?: return
    entered = null
    val handler =
      options().callbacks.hover?.takeIf { subscriptions.hover.contains(previous.subscription) }
        ?: previous.handler
    handler(HoverEvent.Exit(previous.sample))
  }

  companion object {
    private val hoverTypes = setOf(PointerType.Mouse, PointerType.Stylus, PointerType.Eraser)
  }
}
