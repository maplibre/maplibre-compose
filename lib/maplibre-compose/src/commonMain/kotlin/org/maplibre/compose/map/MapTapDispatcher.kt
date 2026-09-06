package org.maplibre.compose.map

import androidx.compose.ui.input.pointer.PointerType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.maplibre.compose.util.ClickResult

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

  fun subscription(subscriptions: InteractionSubscriptions): SubscriptionSlot =
    when (this) {
      Tap -> subscriptions.click
      DoubleTap -> subscriptions.doubleClick
      SecondaryClick,
      LongPress -> subscriptions.contextClick
      TwoFingerTap -> subscriptions.twoFingerClick
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

  fun event(sample: GesturePointerSample): PointerGestureEvent =
    when (this) {
      Tap -> TapEvent(sample)
      DoubleTap -> DoubleTapEvent(sample)
      SecondaryClick,
      LongPress -> ContextClickEvent(sample)
      TwoFingerTap -> TwoFingerTapEvent(sample)
    }

  fun observe(callbacks: InteractionCallbacks, event: PointerGestureEvent): ClickResult =
    when (this) {
      Tap -> callbacks.click?.invoke(event as TapEvent)
      DoubleTap -> callbacks.doubleClick?.invoke(event as DoubleTapEvent)
      SecondaryClick,
      LongPress -> callbacks.contextClick?.invoke(event as ContextClickEvent)
      TwoFingerTap -> callbacks.twoFingerClick?.invoke(event as TwoFingerTapEvent)
    } ?: ClickResult.Pass
}

/** Subscription membership captured at press admission; callback bodies remain current. */
internal class MapTapAdmission(
  val family: TapFamily,
  val path: MapClickPath,
  val mapCallbackSlot: Any?,
  val hasSubscribers: Boolean,
)

/**
 * Captured at press admission; validity is checked between every application callback and query.
 */
internal class MapClickPath(
  val isValid: () -> Boolean,
  val deliver: suspend (PointerGestureEvent) -> ClickResult,
)

/** One input node orders application delivery independently of continuous camera input. */
internal class MapTapDispatcher(
  scope: CoroutineScope,
  private val clicks: MapInteractionTarget,
  private val subscriptions: InteractionSubscriptions,
  private val currentOptions: () -> MapInteractions,
) {
  private class Dispatch(
    val admission: MapTapAdmission,
    val event: PointerGestureEvent,
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
            if (
              admission.family.subscription(subscriptions).contains(admission.mapCallbackSlot) &&
                admission.family.observe(currentOptions().callbacks, dispatch.event).consumed
            )
              continue
            if (!valid(dispatch)) continue
            if (dispatch.admission.path.deliver(dispatch.event).consumed) continue
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

  fun capture(family: TapFamily): MapTapAdmission? =
    clicks.capture(family)?.let {
      val slot = family.subscription(subscriptions).capture()
      MapTapAdmission(family, it, slot, slot != null || family in clicks.capabilities)
    }

  fun dispatch(admission: MapTapAdmission, sample: GesturePointerSample, camera: () -> Unit) {
    queue.trySend(Dispatch(admission, admission.family.event(sample), camera))
  }
}
