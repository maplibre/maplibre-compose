package org.maplibre.compose.interaction

import androidx.compose.runtime.Immutable
import org.maplibre.compose.interaction.internal.CameraComponent
import org.maplibre.compose.interaction.internal.CameraConfiguration
import org.maplibre.compose.interaction.internal.CameraSettings
import org.maplibre.compose.interaction.internal.PanCameraConfiguration
import org.maplibre.compose.interaction.internal.TiltCameraConfiguration
import org.maplibre.compose.interaction.internal.VelocityCameraConfiguration

/** The input source that began a permitted camera response. */
public enum class CameraInputOrigin {
  Drag,
  Transform,
  Scroll,
  Tap,
  TapDrag,
  Key,
  Rotary,
}

/** A component start within an input session. Several components can share [sessionId]. */
@Immutable public data class CameraInputStart(val sessionId: Long, val origin: CameraInputOrigin)

/** Permissions, release momentum, and semantic start callbacks for camera input. */
@MapInteractionDsl
public class CameraBuilder internal constructor(from: CameraConfiguration) {
  private val pan = PanCameraBuilder(from.settings.pan, from.onStart[CameraComponent.Pan])
  private val zoom = VelocityCameraBuilder(from.settings.zoom, from.onStart[CameraComponent.Zoom])
  private val rotate =
    VelocityCameraBuilder(from.settings.rotate, from.onStart[CameraComponent.Rotate])
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
  public fun rotate(block: VelocityCameraBuilder.() -> Unit) {
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
  internal var start: ((CameraInputStart) -> Unit)?,
) {
  public var enabled: Boolean = from.enabled
  private val momentum = PanMomentumBuilder(from.momentum)

  /** Runs before the first effective command each time this component starts. */
  public fun onStart(block: ((CameraInputStart) -> Unit)?) {
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
  internal var start: ((CameraInputStart) -> Unit)?,
) {
  public var enabled: Boolean = from.enabled
  private val momentum = VelocityMomentumBuilder(from.momentum)

  /** Runs before the first effective command each time this component starts. */
  public fun onStart(block: ((CameraInputStart) -> Unit)?) {
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
  internal var start: ((CameraInputStart) -> Unit)?,
) {
  public var enabled: Boolean = from.enabled
  private val momentum = TiltMomentumBuilder(from.momentum)

  /** Runs before the first effective command each time this component starts. */
  public fun onStart(block: ((CameraInputStart) -> Unit)?) {
    start = block
  }

  public fun momentum(block: TiltMomentumBuilder.() -> Unit) {
    momentum.apply(block)
  }

  internal fun build(): TiltCameraConfiguration = TiltCameraConfiguration(enabled, momentum.build())
}
