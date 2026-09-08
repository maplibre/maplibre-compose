package org.maplibre.compose.interaction.internal

import androidx.compose.ui.unit.DpOffset

/** Pending momentum, merged by component and settled once when every contact has lifted. */
internal data class PointerContinuation(
  val pan: GestureMath.Fling? = null,
  val scale: GestureMath.ScaleVelocity? = null,
  val rotation: GestureMath.RotationVelocity? = null,
  val tilt: GestureMath.TiltVelocity? = null,
  val scaleAnchor: DpOffset? = null,
  val rotationAnchor: DpOffset? = null,
) {
  fun settled(): PointerContinuation =
    if (scale != null) copy(pan = pan?.settleWith(scale.duration)) else this

  fun without(component: CameraComponent): PointerContinuation =
    when (component) {
      CameraComponent.Pan -> copy(pan = null)
      CameraComponent.Zoom -> copy(scale = null)
      CameraComponent.Rotate -> copy(rotation = null)
      CameraComponent.Tilt -> copy(tilt = null)
    }

  fun withPrevious(previous: PointerContinuation?): PointerContinuation =
    copy(
      pan = pan ?: previous?.pan,
      scale = scale ?: previous?.scale,
      rotation = rotation ?: previous?.rotation,
      tilt = tilt ?: previous?.tilt,
      scaleAnchor = if (scale != null) scaleAnchor else previous?.scaleAnchor,
      rotationAnchor = if (rotation != null) rotationAnchor else previous?.rotationAnchor,
    )
}
