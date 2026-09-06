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

  fun subscription(subscriptions: InteractionSubscriptions): SubscriptionSlot? =
    when (this) {
      Tap -> subscriptions.click
      DoubleTap -> subscriptions.doubleClick
      SecondaryClick,
      LongPress -> subscriptions.longClick
      TwoFingerTap -> null
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

  fun observe(callbacks: InteractionCallbacks, event: PointerGestureEvent): ClickResult =
    when (this) {
      Tap -> callbacks.click?.invoke(event as TapEvent)
      DoubleTap -> callbacks.doubleClick?.invoke(event as DoubleTapEvent)
      SecondaryClick,
      LongPress -> callbacks.longClick?.invoke(event as LongClickEvent)
      TwoFingerTap -> null
    } ?: ClickResult.Pass
}

/** Subscription membership captured at press admission; callback bodies remain current. */
internal class TapAdmission(
  val family: TapFamily,
  val path: ClickPath,
  val mapCallbackSlot: Any?,
  val hasSubscribers: Boolean,
)

/**
 * Captured at press admission; validity is checked between every application callback and query.
 */
internal class ClickPath(
  val isValid: () -> Boolean,
  val hasSubscribers: Boolean = false,
  val deliver: suspend (PointerGestureEvent) -> ClickResult,
)

/** One input node orders application delivery independently of continuous camera input. */
internal class TapDispatcher(
  scope: CoroutineScope,
  private val captureClickPath: (TapFamily) -> ClickPath?,
  private val subscriptions: InteractionSubscriptions,
  private val currentOptions: () -> MapInteractions,
) {
  private class Dispatch(
    val admission: TapAdmission,
    val event: PointerGestureEvent?,
    val camera: () -> Unit,
  )

  private val queue = Channel<Dispatch>(Channel.UNLIMITED)
  private val structure = currentOptions().structuralKey

  private fun valid(dispatch: Dispatch): Boolean =
    currentOptions().structuralKey == structure && dispatch.admission.path.isValid()

  init {
    scope.launch {
      try {
        for (dispatch in queue) {
          try {
            if (!valid(dispatch)) continue
            val admission = dispatch.admission
            val event = dispatch.event

            // Two-finger taps have only a camera response, so they skip application delivery.
            if (event != null) {
              val subscribed =
                admission.family.subscription(subscriptions)?.contains(admission.mapCallbackSlot) ==
                  true
              if (
                subscribed && admission.family.observe(currentOptions().callbacks, event).consumed
              )
                continue
              if (!valid(dispatch)) continue
              if (admission.path.deliver(event).consumed) continue
            }

            if (valid(dispatch)) dispatch.camera()
          } catch (cancelled: CancellationException) {
            // A lease-bound query can be cancelled without cancelling this attached input node.
            // Drop that dispatch; cancellation never falls through to the camera.
            currentCoroutineContext().ensureActive()
            if (valid(dispatch)) throw cancelled
          }
        }
      } finally {
        queue.cancel()
      }
    }
  }

  fun capture(family: TapFamily): TapAdmission? =
    captureClickPath(family)?.let {
      val slot = family.subscription(subscriptions)?.capture()
      TapAdmission(family, it, slot, slot != null || it.hasSubscribers)
    }

  fun dispatch(admission: TapAdmission, sample: GesturePointerSample, camera: () -> Unit) {
    queue.trySend(Dispatch(admission, admission.family.event(sample), camera))
  }
}
