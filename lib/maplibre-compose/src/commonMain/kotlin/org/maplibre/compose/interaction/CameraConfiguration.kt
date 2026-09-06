package org.maplibre.compose.interaction

import androidx.compose.runtime.Immutable
import org.maplibre.compose.interaction.internal.CameraConfiguration
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
  External,
}

/** A component start within an input session. Several components can share [sessionId]. */
@Immutable public data class CameraInputStart(val sessionId: Long, val origin: CameraInputOrigin)

/** Permissions, release momentum, and semantic start callbacks for camera input. */
@MapInteractionDsl
public class CameraBuilder internal constructor(from: CameraConfiguration) {
  private var value = from

  public fun pan(block: PanCameraBuilder.() -> Unit) {
    value = value.copy(pan = PanCameraBuilder(value.pan).apply(block).build())
  }

  public fun zoom(block: VelocityCameraBuilder.() -> Unit) {
    value = value.copy(zoom = VelocityCameraBuilder(value.zoom).apply(block).build())
  }

  /**
   * Configures bearing input. Release momentum applies to recognized two-pointer rotation;
   * single-pointer rotate/tilt drags and keys add no rotation momentum.
   */
  public fun rotate(block: VelocityCameraBuilder.() -> Unit) {
    value = value.copy(rotate = VelocityCameraBuilder(value.rotate).apply(block).build())
  }

  public fun tilt(block: TiltCameraBuilder.() -> Unit) {
    value = value.copy(tilt = TiltCameraBuilder(value.tilt).apply(block).build())
  }

  internal fun build(): CameraConfiguration = value
}

/** Camera permission and default release momentum for this component. */
@MapInteractionDsl
public class PanCameraBuilder internal constructor(from: PanCameraConfiguration) {
  public var enabled: Boolean = from.enabled
  private val momentum = PanMomentumBuilder(from.momentum)
  private var start = from.onStart

  /** Runs before the first effective command each time this component starts. */
  public fun onStart(block: ((CameraInputStart) -> Unit)?) {
    start = block
  }

  public fun momentum(block: PanMomentumBuilder.() -> Unit) {
    momentum.apply(block)
  }

  internal fun build(): PanCameraConfiguration =
    PanCameraConfiguration(enabled, momentum.build(), start)
}

/** Camera permission and default release momentum for this component. */
@MapInteractionDsl
public class VelocityCameraBuilder internal constructor(from: VelocityCameraConfiguration) {
  public var enabled: Boolean = from.enabled
  private val momentum = VelocityMomentumBuilder(from.momentum)
  private var start = from.onStart

  /** Runs before the first effective command each time this component starts. */
  public fun onStart(block: ((CameraInputStart) -> Unit)?) {
    start = block
  }

  public fun momentum(block: VelocityMomentumBuilder.() -> Unit) {
    momentum.apply(block)
  }

  internal fun build(): VelocityCameraConfiguration =
    VelocityCameraConfiguration(enabled, momentum.build(), start)
}

/** Camera permission and default release momentum for this component. */
@MapInteractionDsl
public class TiltCameraBuilder internal constructor(from: TiltCameraConfiguration) {
  public var enabled: Boolean = from.enabled
  private val momentum = TiltMomentumBuilder(from.momentum)
  private var start = from.onStart

  /** Runs before the first effective command each time this component starts. */
  public fun onStart(block: ((CameraInputStart) -> Unit)?) {
    start = block
  }

  public fun momentum(block: TiltMomentumBuilder.() -> Unit) {
    momentum.apply(block)
  }

  internal fun build(): TiltCameraConfiguration =
    TiltCameraConfiguration(enabled, momentum.build(), start)
}
