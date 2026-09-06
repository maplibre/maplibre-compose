package org.maplibre.compose.interaction.internal

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
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.interaction.DragEvent
import org.maplibre.compose.interaction.GestureAnchor
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.PinchEvent
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.compose.interaction.RotateEvent
import org.maplibre.compose.interaction.ScrollEvent
import org.maplibre.compose.interaction.ShoveEvent
import org.maplibre.spatialk.geojson.Position

internal class GestureIds {
  private var next = 0L

  fun next(): Long = ++next
}

internal fun PointerEvent.gestureSample(
  id: Long,
  target: CameraInputTarget?,
  density: Density,
  offset: androidx.compose.ui.geometry.Offset = changes.first().position,
  types: Set<PointerType> = changes.mapTo(mutableSetOf()) { it.type },
): GesturePointerSample {
  val location = DpOffset((offset.x / density.density).dp, (offset.y / density.density).dp)
  return GesturePointerSample(
    id,
    changes.maxOfOrNull { it.uptimeMillis } ?: 0L,
    location,
    target?.positionFromScreenLocation(location),
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

internal fun GestureAnchor.location(sample: GesturePointerSample): DpOffset? =
  sample.screenOffset.takeIf { this == GestureAnchor.Input }

/** Delivers only to the subscriptions admitted when this lifecycle began. */
internal fun LifecycleMembership.observe(event: DragEvent, current: DragHandlers) {
  when (event) {
    is DragEvent.Start -> if (hasStart) current.onStart?.invoke(event)
    is DragEvent.Delta -> if (hasDelta) current.onDelta?.invoke(event)
    is DragEvent.End -> if (hasEnd) current.onEnd?.invoke(event)
    is DragEvent.Cancel -> if (hasCancel) current.onCancel?.invoke(event)
  }
}

/** Delivers only to the subscriptions admitted when this lifecycle began. */
internal fun LifecycleMembership.observe(event: PinchEvent, current: ZoomHandlers) {
  when (event) {
    is PinchEvent.Start -> if (hasStart) current.onStart?.invoke(event)
    is PinchEvent.Delta -> if (hasDelta) current.onDelta?.invoke(event)
    is PinchEvent.End -> if (hasEnd) current.onEnd?.invoke(event)
    is PinchEvent.Cancel -> if (hasCancel) current.onCancel?.invoke(event)
  }
}

/** Delivers only to the subscriptions admitted when this lifecycle began. */
internal fun LifecycleMembership.observe(event: RotateEvent, current: RotateHandlers) {
  when (event) {
    is RotateEvent.Start -> if (hasStart) current.onStart?.invoke(event)
    is RotateEvent.Delta -> if (hasDelta) current.onDelta?.invoke(event)
    is RotateEvent.End -> if (hasEnd) current.onEnd?.invoke(event)
    is RotateEvent.Cancel -> if (hasCancel) current.onCancel?.invoke(event)
  }
}

/** Delivers only to the subscriptions admitted when this lifecycle began. */
internal fun LifecycleMembership.observe(event: ShoveEvent, current: TiltHandlers) {
  when (event) {
    is ShoveEvent.Start -> if (hasStart) current.onStart?.invoke(event)
    is ShoveEvent.Delta -> if (hasDelta) current.onDelta?.invoke(event)
    is ShoveEvent.End -> if (hasEnd) current.onEnd?.invoke(event)
    is ShoveEvent.Cancel -> if (hasCancel) current.onCancel?.invoke(event)
  }
}

/** Delivers only to the subscriptions admitted when this lifecycle began. */
internal fun LifecycleMembership.observe(event: ScrollEvent, current: ScrollHandlers) {
  when (event) {
    is ScrollEvent.Start -> if (hasStart) current.onStart?.invoke(event)
    is ScrollEvent.Delta -> if (hasDelta) current.onDelta?.invoke(event)
    is ScrollEvent.End -> if (hasEnd) current.onEnd?.invoke(event)
    is ScrollEvent.Cancel -> if (hasCancel) current.onCancel?.invoke(event)
  }
}

internal data class GesturePointerSample(
  val gestureId: Long,
  val uptimeMillis: Long,
  val screenOffset: DpOffset,
  val position: Position?,
  val pointerTypes: Set<PointerType>,
  val buttons: Set<PointerButton>,
  val modifierKeys: Set<KeyModifier>,
)
