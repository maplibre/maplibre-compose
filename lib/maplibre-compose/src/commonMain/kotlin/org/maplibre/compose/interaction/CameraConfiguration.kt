package org.maplibre.compose.interaction

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import org.maplibre.compose.interaction.internal.CameraComponent
import org.maplibre.compose.interaction.internal.CameraConfiguration
import org.maplibre.compose.interaction.internal.CameraSettings
import org.maplibre.compose.interaction.internal.MomentumDefaults
import org.maplibre.compose.interaction.internal.PanCameraConfiguration
import org.maplibre.compose.interaction.internal.PitchCameraConfiguration
import org.maplibre.compose.interaction.internal.RotateCameraConfiguration
import org.maplibre.compose.interaction.internal.ZoomCameraConfiguration

/** Permissions, release momentum, settling, and semantic start callbacks for camera input. */
@MapInteractionDsl
public class CameraBuilder internal constructor(from: CameraConfiguration) {
  private val pan = PanCameraBuilder(from.settings.pan, from.onStart[CameraComponent.Pan])
  private val zoom = ZoomCameraBuilder(from.settings.zoom, from.onStart[CameraComponent.Zoom])
  private val rotate =
    RotateCameraBuilder(from.settings.rotate, from.onStart[CameraComponent.Rotate])
  private val pitch = PitchCameraBuilder(from.settings.pitch, from.onStart[CameraComponent.Pitch])

  /**
   * Configures pan input. Release momentum applies to drags, two-pointer pans, and
   * [org.maplibre.compose.map.MapState.fling]. Scroll and platform pan input use the inertia that
   * the host supplies.
   */
  public fun pan(block: PanCameraBuilder.() -> Unit) {
    pan.apply(block)
  }

  /**
   * Configures zoom input. Release momentum applies to touch pinches, quick zoom, and platform
   * scale gestures. Scroll input uses the inertia that the host supplies.
   */
  public fun zoom(block: ZoomCameraBuilder.() -> Unit) {
    zoom.apply(block)
  }

  /**
   * Configures bearing input. Release momentum applies to recognized two-pointer rotation;
   * single-pointer rotate/pitch drags and keys add no rotation momentum.
   */
  public fun rotate(block: RotateCameraBuilder.() -> Unit) {
    rotate.apply(block)
  }

  /** Configures pitch input. Release momentum applies to pointer pitch drags and shoves. */
  public fun pitch(block: PitchCameraBuilder.() -> Unit) {
    pitch.apply(block)
  }

  internal fun build(): CameraConfiguration =
    CameraConfiguration(
      CameraSettings(pan.build(), zoom.build(), rotate.build(), pitch.build()),
      buildMap {
        pan.start?.let { put(CameraComponent.Pan, it) }
        zoom.start?.let { put(CameraComponent.Zoom, it) }
        rotate.start?.let { put(CameraComponent.Rotate, it) }
        pitch.start?.let { put(CameraComponent.Pitch, it) }
      },
    )
}

/** Pan permission and release momentum. */
@MapInteractionDsl
public class PanCameraBuilder
internal constructor(
  from: PanCameraConfiguration,
  internal var start: (() -> Unit)?,
) {
  public var enabled: Boolean = from.enabled
  private val momentum = MomentumBuilder(from.momentum, MomentumDefaults.PanFriction)

  /** Runs before this component begins responding to input, including restarts within a gesture. */
  public fun onStart(block: (() -> Unit)?) {
    start = block
  }

  /**
   * Configures how a pan keeps moving the map's content after release, in dp and dp per second. On
   * a tilted map, the content moves the same distance on the screen in every direction.
   *
   * On Android, pans ease along [MomentumPlanner.AndroidFling] and need a release of at least 50 dp
   * per second. Elsewhere, they slow by friction with a half-life of 140 ms, stop at 10 dp per
   * second, need a release of at least 200, and count a release faster than 10,000 as 10,000.
   */
  public fun momentum(block: MomentumBuilder.() -> Unit) {
    momentum.apply(block)
  }

  internal fun build(): PanCameraConfiguration = PanCameraConfiguration(enabled, momentum.build())
}

/** Zoom permission, release momentum, and settling. */
@MapInteractionDsl
public class ZoomCameraBuilder
internal constructor(
  from: ZoomCameraConfiguration,
  internal var start: (() -> Unit)?,
) {
  public var enabled: Boolean = from.enabled
  private val momentum = MomentumBuilder(from.momentum, MomentumDefaults.ZoomFriction)
  private var snapping = from.snapping

  /** Runs before this component begins responding to input, including restarts within a gesture. */
  public fun onStart(block: (() -> Unit)?) {
    start = block
  }

  /**
   * Configures how a zoom keeps changing after release, in zoom levels and zoom levels per second.
   *
   * By default, the zoom slows by friction with a half-life of 140 ms, stops at 0.01 levels per
   * second, and moves at most 2.5 levels.
   */
  public fun momentum(block: MomentumBuilder.() -> Unit) {
    momentum.apply(block)
  }

  /**
   * Settles the zoom on a nearby level after zoom input and its momentum end, in zoom levels. Off
   * by default; a block starts from whole levels, within half a level, over 300 ms. Programmatic
   * camera movement is unaffected.
   */
  public fun snapping(block: SnappingBuilder.() -> Unit = {}) {
    snapping = SnappingBuilder(snapping).apply(block).build()
  }

  internal fun build(): ZoomCameraConfiguration =
    ZoomCameraConfiguration(enabled, momentum.build(), snapping)
}

/** Pitch permission, release momentum, and settling. */
@MapInteractionDsl
public class PitchCameraBuilder
internal constructor(
  from: PitchCameraConfiguration,
  internal var start: (() -> Unit)?,
) {
  public var enabled: Boolean = from.enabled
  private val momentum = MomentumBuilder(from.momentum, MomentumDefaults.PitchFriction)
  private var snapping = from.snapping

  /** Runs before this component begins responding to input, including restarts within a gesture. */
  public fun onStart(block: (() -> Unit)?) {
    start = block
  }

  /**
   * Configures how a pitch change continues after release, in degrees and degrees per second.
   *
   * By default, the pitch slows by friction with a half-life of 140 ms, stops at 1 degree per
   * second, and needs a release of at least 5.
   */
  public fun momentum(block: MomentumBuilder.() -> Unit) {
    momentum.apply(block)
  }

  /**
   * Settles the pitch on a nearby angle after pitch input and its momentum end, in degrees. Off by
   * default; a block starts from flat, within 7°, over 300 ms. Programmatic camera movement is
   * unaffected.
   */
  public fun snapping(block: SnappingBuilder.() -> Unit = {}) {
    snapping = SnappingBuilder(snapping).apply(block).build()
  }

  internal fun build(): PitchCameraConfiguration =
    PitchCameraConfiguration(enabled, momentum.build(), snapping)
}

/** Bearing permission, release momentum, settling, and haptic notches. */
@MapInteractionDsl
public class RotateCameraBuilder
internal constructor(
  from: RotateCameraConfiguration,
  internal var start: (() -> Unit)?,
) {
  public var enabled: Boolean = from.enabled
  private val momentum = MomentumBuilder(from.momentum, MomentumDefaults.BearingFriction)
  private var snapping = from.snapping
  private var haptics = from.haptics

  /** Runs before this component begins responding to input, including restarts within a gesture. */
  public fun onStart(block: (() -> Unit)?) {
    start = block
  }

  /**
   * Configures how a rotation keeps turning the map after release, in degrees and degrees per
   * second.
   *
   * By default, the rotation slows by friction with a half-life of 140 ms, stops at 1 degree per
   * second, and needs a release of at least 60.
   */
  public fun momentum(block: MomentumBuilder.() -> Unit) {
    momentum.apply(block)
  }

  /**
   * Settles the bearing on a nearby angle after rotation input and its momentum end, in degrees
   * clockwise from north. By default, the bearing settles on north within 7° over 300 ms.
   * Programmatic camera movement is unaffected. Set `enabled = false` to turn it off.
   */
  public fun snapping(block: SnappingBuilder.() -> Unit = {}) {
    snapping = SnappingBuilder(snapping).apply(block).build()
  }

  /**
   * Replaces the default [HapticFeedbackType.SegmentTick] north notch; an empty block disables
   * feedback. Runs while pointer rotation turns the map, including its momentum and settling, but
   * not for keys or programmatic camera changes. Uses [LocalHapticFeedback], except macOS, which
   * plays the trackpad alignment pattern.
   */
  public fun haptics(block: BearingHapticsBuilder.() -> Unit) {
    haptics = BearingHapticsBuilder().apply(block).build()
  }

  internal fun build(): RotateCameraConfiguration =
    RotateCameraConfiguration(enabled, momentum.build(), snapping, haptics)
}
