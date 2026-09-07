package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.pointer.PointerType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.DoubleTapEvent
import org.maplibre.compose.interaction.LongClickEvent
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.compose.interaction.PointerGestureEvent
import org.maplibre.compose.interaction.TapEvent

internal enum class TapFamily {
  Tap,
  DoubleTap,
  SecondaryClick,
  LongPress,
  TwoFingerTap;

  fun binding(options: MapInteractions): TapBinding =
    with(options.bindings) {
      when (this@TapFamily) {
        Tap -> tap
        DoubleTap -> doubleTap
        SecondaryClick -> secondaryClick
        LongPress -> longPress
        TwoFingerTap -> twoFingerTap
      }
    }

  fun matches(options: MapInteractions, sample: GesturePointerSample): Boolean {
    val binding = binding(options)
    if (!binding.enabled) return false
    val physical =
      when (this) {
        SecondaryClick ->
          PointerType.Mouse in sample.pointerTypes && PointerButton.Secondary in sample.buttons
        LongPress,
        TwoFingerTap ->
          PointerType.Mouse !in sample.pointerTypes && sample.pointerTypes.isNotEmpty()
        Tap,
        DoubleTap -> true
      }
    return physical &&
      PointerPattern(
          binding.pointerTypes,
          if (this == SecondaryClick) PointerButton.Secondary else PointerButton.Primary,
        )
        .matches(sample.pointerTypes, sample.buttons, sample.modifierKeys, contact = true)
  }

  fun event(sample: GesturePointerSample): PointerGestureEvent? =
    when (this) {
      Tap -> TapEvent(sample)
      DoubleTap -> DoubleTapEvent(sample)
      SecondaryClick,
      LongPress -> LongClickEvent(sample)
      TwoFingerTap -> null
    }

  fun hasCallback(callbacks: InteractionCallbacks): Boolean =
    when (this) {
      Tap -> callbacks.click != null
      DoubleTap -> callbacks.doubleClick != null
      SecondaryClick,
      LongPress -> callbacks.longClick != null
      TwoFingerTap -> false
    }

  fun observe(callbacks: InteractionCallbacks, event: PointerGestureEvent): ClickResult =
    when (this) {
      Tap -> callbacks.click?.invoke(event as TapEvent)
      DoubleTap -> callbacks.doubleClick?.invoke(event as DoubleTapEvent)
      SecondaryClick,
      LongPress -> callbacks.longClick?.invoke(event as LongClickEvent)
      TwoFingerTap -> null
    } ?: ClickResult.Pass
}

/** A recognized click keeps its map and layer targets valid across asynchronous feature queries. */
internal class ClickPath(
  val isValid: () -> Boolean,
  val deliver: suspend (PointerGestureEvent) -> ClickResult,
)

/** One input node orders application delivery independently of continuous camera input. */
internal class TapDispatcher(
  scope: CoroutineScope,
  private val captureClickPath: (TapFamily) -> ClickPath?,
  private val hasClickHandlers: (TapFamily) -> Boolean,
  private val currentOptions: () -> MapInteractions,
) {
  private class Dispatch(
    val family: TapFamily,
    val path: ClickPath,
    val event: PointerGestureEvent?,
    val camera: () -> Unit,
  )

  private val queue = Channel<Dispatch>(Channel.UNLIMITED)

  init {
    scope.launch {
      try {
        for (dispatch in queue) {
          try {
            if (!dispatch.path.isValid()) continue
            val event = dispatch.event

            // Two-finger taps have only a camera response, so they skip application delivery.
            if (event != null) {
              if (dispatch.family.observe(currentOptions().callbacks, event).consumed) continue
              if (!dispatch.path.isValid()) continue
              if (dispatch.path.deliver(event).consumed) continue
            }

            if (dispatch.path.isValid()) dispatch.camera()
          } catch (cancelled: CancellationException) {
            // A lease-bound query can be cancelled without cancelling this attached input node.
            // Drop that dispatch; cancellation never falls through to the camera.
            currentCoroutineContext().ensureActive()
            if (dispatch.path.isValid()) throw cancelled
          }
        }
      } finally {
        queue.cancel()
      }
    }
  }

  fun hasHandlers(family: TapFamily): Boolean =
    family.hasCallback(currentOptions().callbacks) || hasClickHandlers(family)

  fun dispatch(family: TapFamily, sample: GesturePointerSample, camera: () -> Unit) {
    val path = captureClickPath(family) ?: return
    queue.trySend(Dispatch(family, path, family.event(sample), camera))
  }
}
