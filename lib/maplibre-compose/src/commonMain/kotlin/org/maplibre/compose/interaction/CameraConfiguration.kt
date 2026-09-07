package org.maplibre.compose.interaction

import org.maplibre.compose.interaction.internal.CameraComponent
import org.maplibre.compose.interaction.internal.CameraConfiguration
import org.maplibre.compose.interaction.internal.CameraSettings
import org.maplibre.compose.interaction.internal.PanCameraConfiguration
import org.maplibre.compose.interaction.internal.RotateCameraConfiguration
import org.maplibre.compose.interaction.internal.TiltCameraConfiguration
import org.maplibre.compose.interaction.internal.VelocityCameraConfiguration

/** Permissions, release momentum, and semantic start callbacks for camera input. */
@MapInteractionDsl
public class CameraBuilder internal constructor(from: CameraConfiguration) {
  private val pan = PanCameraBuilder(from.settings.pan, from.onStart[CameraComponent.Pan])
  private val zoom = VelocityCameraBuilder(from.settings.zoom, from.onStart[CameraComponent.Zoom])
  private val rotate =
    RotateCameraBuilder(from.settings.rotate, from.onStart[CameraComponent.Rotate])
  private val tilt = TiltCameraBuilder(from.settings.tilt, from.onStart[CameraComponent.Tilt])

  public fun pan(block: PanCameraBuilder.() -> Unit) {
    pan.apply(block)
  }

  public fun zoom(block: VelocityCameraBuilder.() -> Unit) {
    zoom.apply(block)
  }

  /**
   * Configures bearing input. Release momentum applies to recognized two-pointer rotation;
   * single-pointer rotate/tilt drags and keys add no rotation momentum.
   */
  public fun rotate(block: RotateCameraBuilder.() -> Unit) {
    rotate.apply(block)
  }

  public fun tilt(block: TiltCameraBuilder.() -> Unit) {
    tilt.apply(block)
  }

  internal fun build(): CameraConfiguration =
    CameraConfiguration(
      CameraSettings(pan.build(), zoom.build(), rotate.build(), tilt.build()),
      buildMap {
        pan.start?.let { put(CameraComponent.Pan, it) }
        zoom.start?.let { put(CameraComponent.Zoom, it) }
        rotate.start?.let { put(CameraComponent.Rotate, it) }
        tilt.start?.let { put(CameraComponent.Tilt, it) }
      },
    )
}

/** Camera permission and release momentum for this component. */
@MapInteractionDsl
public class PanCameraBuilder
internal constructor(
  from: PanCameraConfiguration,
  internal var start: (() -> Unit)?,
) {
  public var enabled: Boolean = from.enabled
  private val momentum = PanMomentumBuilder(from.momentum)

  /** Runs before this component begins responding to input, including restarts within a gesture. */
  public fun onStart(block: (() -> Unit)?) {
    start = block
  }

  public fun momentum(block: PanMomentumBuilder.() -> Unit) {
    momentum.apply(block)
  }

  internal fun build(): PanCameraConfiguration = PanCameraConfiguration(enabled, momentum.build())
}

/** Camera permission and release momentum for this component. */
@MapInteractionDsl
public class VelocityCameraBuilder
internal constructor(
  from: VelocityCameraConfiguration,
  internal var start: (() -> Unit)?,
) {
  public var enabled: Boolean = from.enabled
  private val momentum = VelocityMomentumBuilder(from.momentum)

  /** Runs before this component begins responding to input, including restarts within a gesture. */
  public fun onStart(block: (() -> Unit)?) {
    start = block
  }

  public fun momentum(block: VelocityMomentumBuilder.() -> Unit) {
    momentum.apply(block)
  }

  internal fun build(): VelocityCameraConfiguration =
    VelocityCameraConfiguration(enabled, momentum.build())
}

/** Camera permission and release momentum for this component. */
@MapInteractionDsl
public class TiltCameraBuilder
internal constructor(
  from: TiltCameraConfiguration,
  internal var start: (() -> Unit)?,
) {
  public var enabled: Boolean = from.enabled
  private val momentum = TiltMomentumBuilder(from.momentum)

  /** Runs before this component begins responding to input, including restarts within a gesture. */
  public fun onStart(block: (() -> Unit)?) {
    start = block
  }

  public fun momentum(block: TiltMomentumBuilder.() -> Unit) {
    momentum.apply(block)
  }

  internal fun build(): TiltCameraConfiguration = TiltCameraConfiguration(enabled, momentum.build())
}

/** Bearing permission, release momentum, and optional settlement after user input. */
@MapInteractionDsl
public class RotateCameraBuilder
internal constructor(
  from: RotateCameraConfiguration,
  internal var start: (() -> Unit)?,
) {
  public var enabled: Boolean = from.enabled
  private val momentum = VelocityMomentumBuilder(from.momentum)
  private var snapping = from.snapping
  private var haptics = from.haptics

  public fun onStart(block: (() -> Unit)?) {
    start = block
  }

  public fun momentum(block: VelocityMomentumBuilder.() -> Unit) {
    momentum.apply(block)
  }

  /**
   * Enables settlement after rotation input and momentum end. Uses the interaction animation
   * duration and preserves center, zoom, and tilt. Programmatic camera movement is unaffected. New
   * input or camera control cancels settlement. Set `enabled = false` to disable inherited
   * snapping.
   */
  public fun snapping(block: BearingSnappingBuilder.() -> Unit = {}) {
    snapping = BearingSnappingBuilder(snapping).apply(block).build()
  }

  /**
   * Replaces haptic notches; an empty block disables them. Feedback is opt-in and runs during
   * pointer rotation, not momentum, settlement, keys, or programmatic camera changes. iOS, Android,
   * and macOS use platform feedback subject to hardware and system settings. Other platforms are
   * silent.
   */
  public fun haptics(block: BearingHapticsBuilder.() -> Unit) {
    haptics = BearingHapticsBuilder().apply(block).build()
  }

  internal fun build(): RotateCameraConfiguration =
    RotateCameraConfiguration(enabled, momentum.build(), snapping, haptics)
}
