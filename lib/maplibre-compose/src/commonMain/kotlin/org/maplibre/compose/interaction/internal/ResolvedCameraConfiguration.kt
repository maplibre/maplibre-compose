package org.maplibre.compose.interaction.internal

import org.maplibre.compose.interaction.CameraInputStart

internal enum class CameraComponent {
  Pan,
  Zoom,
  Rotate,
  Tilt,
}

internal data class CameraConfiguration(
  val pan: PanCameraConfiguration = PanCameraConfiguration(),
  val zoom: VelocityCameraConfiguration = VelocityCameraConfiguration(),
  val rotate: VelocityCameraConfiguration = VelocityCameraConfiguration(),
  val tilt: TiltCameraConfiguration = TiltCameraConfiguration(),
) {
  val structuralKey: Any
    get() =
      listOf(
        pan.enabled,
        pan.momentum,
        zoom.enabled,
        zoom.momentum,
        rotate.enabled,
        rotate.momentum,
        tilt.enabled,
        tilt.momentum,
      )

  fun enabled(component: CameraComponent): Boolean =
    when (component) {
      CameraComponent.Pan -> pan.enabled
      CameraComponent.Zoom -> zoom.enabled
      CameraComponent.Rotate -> rotate.enabled
      CameraComponent.Tilt -> tilt.enabled
    }

  fun onStart(component: CameraComponent): ((CameraInputStart) -> Unit)? =
    when (component) {
      CameraComponent.Pan -> pan.onStart
      CameraComponent.Zoom -> zoom.onStart
      CameraComponent.Rotate -> rotate.onStart
      CameraComponent.Tilt -> tilt.onStart
    }
}

internal data class PanCameraConfiguration(
  val enabled: Boolean = true,
  val momentum: PanMomentum = PanMomentum(),
  val onStart: ((CameraInputStart) -> Unit)? = null,
)

internal data class VelocityCameraConfiguration(
  val enabled: Boolean = true,
  val momentum: VelocityMomentum = VelocityMomentum(),
  val onStart: ((CameraInputStart) -> Unit)? = null,
)

internal data class TiltCameraConfiguration(
  val enabled: Boolean = true,
  val momentum: TiltMomentum = TiltMomentum(),
  val onStart: ((CameraInputStart) -> Unit)? = null,
)
