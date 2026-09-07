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
import org.maplibre.compose.interaction.GestureAnchor
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.spatialk.geojson.Position

internal fun PointerEvent.gestureSample(
  target: CameraInputTarget?,
  density: Density,
  offset: androidx.compose.ui.geometry.Offset = changes.first().position,
  types: Set<PointerType> = changes.mapTo(mutableSetOf()) { it.type },
): GesturePointerSample {
  val location = DpOffset((offset.x / density.density).dp, (offset.y / density.density).dp)
  return GesturePointerSample(
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

internal data class GesturePointerSample(
  val uptimeMillis: Long,
  val screenOffset: DpOffset,
  val position: Position?,
  val pointerTypes: Set<PointerType>,
  val buttons: Set<PointerButton>,
  val modifierKeys: Set<KeyModifier>,
)
