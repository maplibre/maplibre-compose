package org.maplibre.compose.interaction.internal

import org.maplibre.compose.interaction.CameraInputStart

internal enum class CameraComponent {
  Pan,
  Zoom,
  Rotate,
  Tilt,
}

internal data class CameraConfiguration(
  val settings: CameraSettings = CameraSettings(),
  val onStart: Map<CameraComponent, (CameraInputStart) -> Unit> = emptyMap(),
)

internal data class CameraSettings(
  val pan: PanCameraConfiguration = PanCameraConfiguration(),
  val zoom: VelocityCameraConfiguration = VelocityCameraConfiguration(),
  val rotate: VelocityCameraConfiguration = VelocityCameraConfiguration(),
  val tilt: TiltCameraConfiguration = TiltCameraConfiguration(),
) {
  fun enabled(component: CameraComponent): Boolean =
    when (component) {
      CameraComponent.Pan -> pan.enabled
      CameraComponent.Zoom -> zoom.enabled
      CameraComponent.Rotate -> rotate.enabled
      CameraComponent.Tilt -> tilt.enabled
    }
}

internal data class PanCameraConfiguration(
  val enabled: Boolean = true,
  val momentum: PanMomentum = PanMomentum(),
)

internal data class VelocityCameraConfiguration(
  val enabled: Boolean = true,
  val momentum: VelocityMomentum = VelocityMomentum(),
)

internal data class TiltCameraConfiguration(
  val enabled: Boolean = true,
  val momentum: TiltMomentum = TiltMomentum(),
)
