package org.maplibre.compose.interaction.internal

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import org.maplibre.compose.interaction.BearingHapticNotch

internal enum class CameraComponent {
  Pan,
  Zoom,
  Rotate,
  Pitch,
}

internal data class CameraConfiguration(
  val settings: CameraSettings = CameraSettings(),
  val onStart: Map<CameraComponent, () -> Unit> = emptyMap(),
)

internal data class CameraSettings(
  val pan: PanCameraConfiguration = PanCameraConfiguration(),
  val zoom: ZoomCameraConfiguration = ZoomCameraConfiguration(),
  val rotate: RotateCameraConfiguration = RotateCameraConfiguration(),
  val pitch: PitchCameraConfiguration = PitchCameraConfiguration(),
) {
  fun enabled(component: CameraComponent): Boolean =
    when (component) {
      CameraComponent.Pan -> pan.enabled
      CameraComponent.Zoom -> zoom.enabled
      CameraComponent.Rotate -> rotate.enabled
      CameraComponent.Pitch -> pitch.enabled
    }
}

internal data class PanCameraConfiguration(
  val enabled: Boolean = true,
  val momentum: Momentum = Momentum(true, PlatformPanDecay),
)

internal data class ZoomCameraConfiguration(
  val enabled: Boolean = true,
  val momentum: Momentum = Momentum(true, MomentumDefaults.ZoomFriction),
  val snapping: Snapping = MomentumDefaults.ZoomSnapping,
)

internal data class PitchCameraConfiguration(
  val enabled: Boolean = true,
  val momentum: Momentum = Momentum(true, MomentumDefaults.PitchFriction),
  val snapping: Snapping = MomentumDefaults.PitchSnapping,
)

internal data class RotateCameraConfiguration(
  val enabled: Boolean = true,
  val momentum: Momentum = Momentum(true, MomentumDefaults.BearingFriction),
  val snapping: Snapping = MomentumDefaults.BearingSnapping,
  val haptics: List<BearingHapticNotch> =
    listOf(BearingHapticNotch(360.0, 0.0, HapticFeedbackType.SegmentTick)),
)
