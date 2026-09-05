package org.maplibre.compose.map

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

internal class GestureIds {
  private var next = 0L

  fun next(): Long = ++next
}

internal fun PointerEvent.gestureSample(
  id: Long,
  target: GestureTarget,
  density: Density,
  offset: androidx.compose.ui.geometry.Offset = changes.first().position,
  types: Set<PointerType> = changes.mapTo(mutableSetOf()) { it.type },
): GesturePointerSample {
  val location = DpOffset((offset.x / density.density).dp, (offset.y / density.density).dp)
  return GesturePointerSample(
    id,
    changes.maxOfOrNull { it.uptimeMillis } ?: 0L,
    location,
    target.positionFromScreenLocation(location),
    types,
    buildSet {
      if (buttons.isPrimaryPressed) add(PointerButton.Primary)
      if (buttons.isSecondaryPressed) add(PointerButton.Secondary)
      if (buttons.isTertiaryPressed) add(PointerButton.Tertiary)
      if (buttons.isBackPressed) add(PointerButton.Back)
      if (buttons.isForwardPressed) add(PointerButton.Forward)
    },
    buildSet {
      if (keyboardModifiers.isShiftPressed) add(KeyModifier.Shift)
      if (keyboardModifiers.isCtrlPressed) add(KeyModifier.Ctrl)
      if (keyboardModifiers.isAltPressed) add(KeyModifier.Alt)
      if (keyboardModifiers.isMetaPressed) add(KeyModifier.Meta)
    },
  )
}

internal fun GestureBinding.matches(sample: GesturePointerSample, contact: Boolean): Boolean =
  enabled &&
    filters.any {
      it.matches(sample.pointerTypes, sample.buttons, sample.modifierKeys, contact)
    }

internal fun GestureBinding.anchor(sample: GesturePointerSample): DpOffset? =
  sample.screenOffset.takeIf { settings.anchor == GestureAnchor.Input }

internal fun GestureBindingHandlers.observe(event: ScrollEvent) {
  when (event) {
    is ScrollEvent.Start -> scrollStart?.invoke(event)
    is ScrollEvent.Delta -> scrollDelta?.invoke(event)
    is ScrollEvent.End -> scrollEnd?.invoke(event)
    is ScrollEvent.Cancel -> scrollCancel?.invoke(event)
  }
}

internal fun GestureBindingHandlers.observe(event: DragEvent) {
  dragEvent?.invoke(event)
  when (event) {
    is DragEvent.Start -> dragStart?.invoke(event)
    is DragEvent.Delta -> dragDelta?.invoke(event)
    is DragEvent.End -> dragEnd?.invoke(event)
    is DragEvent.Cancel -> dragCancel?.invoke(event)
  }
}

internal fun GestureBindingHandlers.observe(event: PinchEvent) {
  when (event) {
    is PinchEvent.Start -> pinchStart?.invoke(event)
    is PinchEvent.Delta -> pinchDelta?.invoke(event)
    is PinchEvent.End -> pinchEnd?.invoke(event)
    is PinchEvent.Cancel -> pinchCancel?.invoke(event)
  }
}

internal fun GestureBindingHandlers.observe(event: RotateEvent) {
  when (event) {
    is RotateEvent.Start -> rotateStart?.invoke(event)
    is RotateEvent.Delta -> rotateDelta?.invoke(event)
    is RotateEvent.End -> rotateEnd?.invoke(event)
    is RotateEvent.Cancel -> rotateCancel?.invoke(event)
  }
}

internal fun GestureBindingHandlers.observe(event: ShoveEvent) {
  when (event) {
    is ShoveEvent.Start -> shoveStart?.invoke(event)
    is ShoveEvent.Delta -> shoveDelta?.invoke(event)
    is ShoveEvent.End -> shoveEnd?.invoke(event)
    is ShoveEvent.Cancel -> shoveCancel?.invoke(event)
  }
}
