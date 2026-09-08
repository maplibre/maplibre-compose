package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.pointer.PointerType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.maplibre.compose.interaction.ClickEvent
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.PointerButton

internal enum class TapFamily {
  Tap,
  DoubleTap,
  SecondaryClick,
  LongPress,
  TwoFingerTap;

  fun binding(options: InputConfiguration): TapBinding =
    with(options.bindings) {
      when (this@TapFamily) {
        Tap -> tap
        DoubleTap -> doubleTap
        SecondaryClick -> secondaryClick
        LongPress -> longPress
        TwoFingerTap -> twoFingerTap
      }
    }

  fun matches(options: InputConfiguration, sample: GesturePointerSample): Boolean {
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

  fun callback(callbacks: InteractionCallbacks): ((ClickEvent) -> ClickResult)? =
    when (this) {
      Tap -> callbacks.click
      DoubleTap -> callbacks.doubleClick
      SecondaryClick,
      LongPress -> callbacks.longClick
      TwoFingerTap -> null
    }
}

/** A recognized click keeps its map and layer targets valid across asynchronous feature queries. */
internal class ClickPath(
  val isValid: () -> Boolean,
  val deliver: suspend (ClickEvent) -> ClickResult,
)

/** One input node orders application delivery independently of continuous camera input. */
internal class TapDispatcher(
  scope: CoroutineScope,
  private val captureClickPath: (TapFamily) -> ClickPath?,
  private val hasClickHandlers: (TapFamily) -> Boolean,
  private val currentOptions: () -> InputConfiguration,
) {
  private class Dispatch(
    val family: TapFamily,
    val path: ClickPath,
    val event: ClickEvent?,
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
              val callback = dispatch.family.callback(currentOptions().callbacks)
              if (callback?.invoke(event)?.consumed == true) continue
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
    family.callback(currentOptions().callbacks) != null || hasClickHandlers(family)

  fun dispatch(family: TapFamily, sample: GesturePointerSample, camera: () -> Unit) {
    val path = captureClickPath(family) ?: return
    val event = if (family == TapFamily.TwoFingerTap) null else ClickEvent(sample)
    queue.trySend(Dispatch(family, path, event, camera))
  }
}
