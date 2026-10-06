package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import org.maplibre.compose.interaction.CameraAction
import org.maplibre.compose.interaction.DragAction
import org.maplibre.compose.interaction.FocusAction
import org.maplibre.compose.interaction.InputAction
import org.maplibre.compose.interaction.KeyAction
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.compose.interaction.ReportedKeyModifiers
import org.maplibre.compose.interaction.ScrollAction
import org.maplibre.compose.interaction.TapAction
import org.maplibre.compose.interaction.UnspecifiedAction

internal fun PointerPattern.matches(
  sample: GesturePointerSample,
  contact: Boolean = true,
): Boolean = matches(sample.pointerTypes, sample.buttons, sample.modifierKeys, contact)

private fun eligible(
  enabled: Boolean,
  pointerTypes: Set<PointerType>?,
  sample: GesturePointerSample,
): Boolean = enabled && (pointerTypes == null || sample.pointerTypes.all { it in pointerTypes })

internal fun DragBinding.matches(sample: GesturePointerSample): Boolean =
  eligible(enabled, pointerTypes, sample)

internal fun ScrollBinding.matches(sample: GesturePointerSample): Boolean =
  eligible(enabled, pointerTypes, sample)

internal fun TapBinding.matches(sample: GesturePointerSample): Boolean =
  eligible(enabled, pointerTypes, sample)

internal fun TapDragBinding.matches(sample: GesturePointerSample): Boolean =
  eligible(enabled, pointerTypes, sample) &&
    (modifiers?.matches(sample.modifierKeys) != false) &&
    PointerPattern(button = PointerButton.Primary).matches(sample)

internal fun CameraSettings.permits(action: DragAction): Boolean =
  when (action) {
    CameraAction.Pan -> pan.enabled
    CameraAction.RotatePitch -> rotate.enabled || pitch.enabled
    CameraAction.FitBounds -> pan.enabled && zoom.enabled
    InputAction.None,
    UnspecifiedAction -> true
  }

internal fun CameraSettings.permits(action: ScrollAction): Boolean =
  when (action) {
    CameraAction.Pan -> pan.enabled
    CameraAction.Zoom -> zoom.enabled
    InputAction.None,
    UnspecifiedAction -> true
  }

internal fun CameraSettings.permits(action: TapAction): Boolean =
  when (action) {
    CameraAction.ZoomIn,
    CameraAction.ZoomOut -> zoom.enabled
    InputAction.None,
    UnspecifiedAction -> true
  }

internal fun CameraSettings.permits(action: KeyAction): Boolean =
  when (action) {
    CameraAction.PanLeft,
    CameraAction.PanRight,
    CameraAction.PanUp,
    CameraAction.PanDown -> pan.enabled
    CameraAction.ZoomIn,
    CameraAction.ZoomOut -> zoom.enabled
    CameraAction.RotateLeft,
    CameraAction.RotateRight -> rotate.enabled
    CameraAction.PitchUp,
    CameraAction.PitchDown -> pitch.enabled
    FocusAction.Engage,
    FocusAction.Disengage,
    FocusAction.Back,
    InputAction.None,
    UnspecifiedAction -> true
  }

internal fun DragBinding.select(
  sample: GesturePointerSample,
  camera: CameraSettings,
): DragAction? =
  if (!matches(sample)) null
  else
    mappings
      .firstOrNull {
        it.pattern.matches(sample) && camera.permits(it.action)
      }
      ?.action

internal fun ScrollBinding.select(
  sample: GesturePointerSample,
  camera: CameraSettings,
): ScrollAction? =
  if (!matches(sample)) null
  else
    mappings
      .firstOrNull {
        it.pattern.matches(sample, contact = false) && camera.permits(it.action)
      }
      ?.action

internal fun TapBinding.select(
  sample: GesturePointerSample,
  camera: CameraSettings,
): TapAction? =
  if (!matches(sample)) null
  else
    mappings
      .firstOrNull {
        it.pattern.matches(sample) && camera.permits(it.action)
      }
      ?.action

internal fun KeyBinding.select(
  key: Key,
  modifiers: Set<KeyModifier>,
  camera: CameraSettings,
): KeyAction? =
  if (!enabled) null
  else
    mappings
      .firstOrNull {
        (it.key == null || it.key == key) &&
          (it.modifiers?.matches(modifiers) != false) &&
          camera.permits(it.action)
      }
      ?.action

/** Exhaustive modifier enumeration proves reachability without running application code. */
internal fun KeyBinding.hasCameraBindings(camera: CameraSettings): Boolean {
  if (!enabled) return false
  val explicitKeys = mappings.mapNotNull { it.key }.toSet()
  // A representative outside the explicit finite table tests catch-all reachability.
  var unmatched = Long.MAX_VALUE
  while (Key(unmatched) in explicitKeys) unmatched--
  val keys = explicitKeys + Key(unmatched)
  return keys.any { key ->
    (0 until (1 shl ReportedKeyModifiers.size)).any { mask ->
      val modifiers =
        ReportedKeyModifiers.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
      select(key, modifiers, camera)?.isCamera == true
    }
  }
}

internal fun TransformPanBinding.matches(sample: GesturePointerSample): Boolean =
  eligible(enabled, pointerTypes, sample) && (modifiers?.matches(sample.modifierKeys) != false)

internal fun TransformZoomBinding.matches(sample: GesturePointerSample): Boolean =
  eligible(enabled, pointerTypes, sample) && (modifiers?.matches(sample.modifierKeys) != false)

internal fun TransformRotateBinding.matches(sample: GesturePointerSample): Boolean =
  eligible(enabled, pointerTypes, sample) && (modifiers?.matches(sample.modifierKeys) != false)

internal fun TransformPitchBinding.matches(sample: GesturePointerSample): Boolean =
  eligible(enabled, pointerTypes, sample) && (modifiers?.matches(sample.modifierKeys) != false)

internal fun TransformBinding.hasDemand(
  sample: GesturePointerSample,
  camera: CameraSettings,
): Boolean =
  (camera.pan.enabled && pan.matches(sample)) ||
    (camera.zoom.enabled && zoom.matches(sample)) ||
    (camera.rotate.enabled && rotate.matches(sample)) ||
    (camera.pitch.enabled && pitch.matches(sample))
