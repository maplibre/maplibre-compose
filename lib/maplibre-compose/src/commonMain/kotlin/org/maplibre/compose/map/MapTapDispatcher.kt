package org.maplibre.compose.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.maplibre.compose.util.ClickResult

internal enum class TapFamily(val bindingId: String) {
  Tap("tap"),
  DoubleTap("doubleTap"),
  LongPress("longPress"),
  TwoFingerTap("twoFingerTap");

  fun event(sample: GesturePointerSample): PointerGestureEvent =
    when (this) {
      Tap -> TapEvent(sample)
      DoubleTap -> DoubleTapEvent(sample)
      LongPress -> LongPressEvent(sample)
      TwoFingerTap -> TwoFingerTapEvent(sample)
    }

  fun hasHandler(handlers: GestureBindingHandlers): Boolean =
    when (this) {
      Tap -> handlers.tap != null
      DoubleTap -> handlers.doubleTap != null
      LongPress -> handlers.longPress != null
      TwoFingerTap -> handlers.twoFingerTap != null
    }

  fun observe(handlers: GestureBindingHandlers, event: PointerGestureEvent): ClickResult =
    when (this) {
      Tap -> handlers.tap?.invoke(event as TapEvent)
      DoubleTap -> handlers.doubleTap?.invoke(event as DoubleTapEvent)
      LongPress -> handlers.longPress?.invoke(event as LongPressEvent)
      TwoFingerTap -> handlers.twoFingerTap?.invoke(event as TwoFingerTapEvent)
    } ?: ClickResult.Pass
}

/** Captured at recognition; validity is checked between every application callback and query. */
internal class MapClickPath(
  val isValid: () -> Boolean,
  val deliver: suspend (PointerGestureEvent) -> ClickResult,
)

/** One input node orders application delivery independently of continuous camera input. */
internal class MapTapDispatcher(
  scope: CoroutineScope,
  private val clicks: MapInteractionTarget,
  private val currentOptions: () -> MapGestures,
) {
  private class Dispatch(
    val family: TapFamily,
    val event: PointerGestureEvent,
    val path: MapClickPath,
    val camera: () -> Unit,
  )

  private val queue = Channel<Dispatch>(Channel.UNLIMITED)
  private val structure = currentOptions().structuralKey

  private fun valid(dispatch: Dispatch): Boolean =
    currentOptions().structuralKey == structure && dispatch.path.isValid()

  init {
    scope.launch {
      try {
        for (dispatch in queue) {
          try {
            if (!valid(dispatch)) continue
            val handlers = currentOptions().binding(dispatch.family.bindingId).handlers
            if (dispatch.family.observe(handlers, dispatch.event).consumed) continue
            if (!valid(dispatch)) continue
            if (dispatch.path.deliver(dispatch.event).consumed) continue
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

  fun dispatch(family: TapFamily, sample: GesturePointerSample, camera: () -> Unit) {
    val path = clicks.capture(family) ?: return
    queue.trySend(Dispatch(family, family.event(sample), path, camera))
  }
}
