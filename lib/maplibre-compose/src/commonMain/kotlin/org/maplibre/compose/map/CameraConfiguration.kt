package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

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
   * single-pointer rotate/tilt drags, keys, and external commands add no rotation momentum.
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
