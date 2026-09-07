package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.PointerButton

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
    modifiers.matches(sample.modifierKeys) &&
    PointerType.Mouse !in sample.pointerTypes &&
    PointerPattern(button = PointerButton.Primary).matches(sample)

internal fun CameraSettings.permits(response: DragResponse): Boolean =
  when (response) {
    DragResponse.Pan -> pan.enabled
    DragResponse.RotateTilt -> rotate.enabled || tilt.enabled
    DragResponse.FitBounds -> pan.enabled && zoom.enabled
    DragResponse.None -> true
  }

internal fun CameraSettings.permits(response: ScrollResponse): Boolean =
  when (response) {
    ScrollResponse.Pan -> pan.enabled
    ScrollResponse.Zoom -> zoom.enabled
    ScrollResponse.None -> true
  }

internal fun CameraSettings.permits(response: TapResponse): Boolean =
  response == TapResponse.None || zoom.enabled

internal fun CameraSettings.permits(response: KeyResponse): Boolean =
  when (response) {
    KeyResponse.PanLeft,
    KeyResponse.PanRight,
    KeyResponse.PanUp,
    KeyResponse.PanDown -> pan.enabled
    KeyResponse.ZoomIn,
    KeyResponse.ZoomOut -> zoom.enabled
    KeyResponse.RotateLeft,
    KeyResponse.RotateRight -> rotate.enabled
    KeyResponse.TiltUp,
    KeyResponse.TiltDown -> tilt.enabled
    KeyResponse.Engage,
    KeyResponse.Disengage,
    KeyResponse.Back,
    KeyResponse.None -> true
  }

internal fun DragBinding.select(
  sample: GesturePointerSample,
  camera: CameraSettings,
): DragResponse? =
  if (!matches(sample)) null
  else
    mappings
      .firstOrNull {
        it.pattern.matches(sample) && camera.permits(it.response)
      }
      ?.response

internal fun ScrollBinding.select(
  sample: GesturePointerSample,
  camera: CameraSettings,
): ScrollResponse? =
  if (!matches(sample)) null
  else
    mappings
      .firstOrNull {
        it.pattern.matches(sample, contact = false) && camera.permits(it.response)
      }
      ?.response

internal fun TapBinding.select(
  sample: GesturePointerSample,
  camera: CameraSettings,
): TapResponse? =
  if (!matches(sample)) null
  else
    mappings
      .firstOrNull {
        it.pattern.matches(sample) && camera.permits(it.response)
      }
      ?.response

internal fun KeyBinding.select(
  key: Key,
  modifiers: Set<KeyModifier>,
  camera: CameraSettings,
): KeyResponse? =
  if (!enabled) null
  else
    mappings
      .firstOrNull {
        (it.key == null || it.key == key) &&
          it.modifiers.matches(modifiers) &&
          camera.permits(it.response)
      }
      ?.response

/** Exhaustive modifier enumeration proves reachability without running application code. */
internal fun KeyBinding.hasCameraBindings(camera: CameraSettings): Boolean {
  if (!enabled) return false
  val explicitKeys = mappings.mapNotNull { it.key }.toSet()
  // A representative outside the explicit finite table tests catch-all reachability.
  var unmatched = Long.MAX_VALUE
  while (Key(unmatched) in explicitKeys) unmatched--
  val keys = explicitKeys + Key(unmatched)
  return keys.any { key ->
    (0 until (1 shl KeyModifier.entries.size)).any { mask ->
      val modifiers =
        KeyModifier.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
      select(key, modifiers, camera)?.isCamera == true
    }
  }
}

internal fun TransformPanBinding.matches(sample: GesturePointerSample): Boolean =
  eligible(enabled, pointerTypes, sample) && modifiers.matches(sample.modifierKeys)

internal fun TransformZoomBinding.matches(sample: GesturePointerSample): Boolean =
  eligible(enabled, pointerTypes, sample) && modifiers.matches(sample.modifierKeys)

internal fun TransformRotateBinding.matches(sample: GesturePointerSample): Boolean =
  eligible(enabled, pointerTypes, sample) && modifiers.matches(sample.modifierKeys)

internal fun TransformTiltBinding.matches(sample: GesturePointerSample): Boolean =
  eligible(enabled, pointerTypes, sample) && modifiers.matches(sample.modifierKeys)

internal fun TransformBinding.hasDemand(
  sample: GesturePointerSample,
  camera: CameraSettings,
): Boolean =
  (camera.pan.enabled && pan.matches(sample)) ||
    (camera.zoom.enabled && zoom.matches(sample)) ||
    (camera.rotate.enabled && rotate.matches(sample)) ||
    (camera.tilt.enabled && tilt.matches(sample))
