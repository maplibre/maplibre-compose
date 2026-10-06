package org.maplibre.compose.interaction

import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import kotlin.time.Duration
import org.maplibre.compose.interaction.internal.DragBinding
import org.maplibre.compose.interaction.internal.DragFitBoundsSettings
import org.maplibre.compose.interaction.internal.DragPanSettings
import org.maplibre.compose.interaction.internal.DragRotatePitchSettings
import org.maplibre.compose.interaction.internal.InteractionBindings
import org.maplibre.compose.interaction.internal.KeyBinding
import org.maplibre.compose.interaction.internal.RotaryBinding
import org.maplibre.compose.interaction.internal.ScrollBinding
import org.maplibre.compose.interaction.internal.TapBinding
import org.maplibre.compose.interaction.internal.TapDragBinding
import org.maplibre.compose.interaction.internal.TransformBinding
import org.maplibre.compose.interaction.internal.TransformPanBinding
import org.maplibre.compose.interaction.internal.TransformPitchBinding
import org.maplibre.compose.interaction.internal.TransformRotateBinding
import org.maplibre.compose.interaction.internal.TransformZoomBinding
import org.maplibre.compose.interaction.internal.requireNonnegativeFinite

@MapInteractionDsl
public class DragPanBuilder internal constructor(from: DragPanSettings) {
  /**
   * Recognition distance for non-mouse pointers, in dp. [Dp.Unspecified] (the default) uses
   * Compose's local touch slop.
   */
  public var startSlop: Dp = from.startSlop

  /** Recognition distance for mouse pointers, in dp; independent of [startSlop]. */
  public var mouseStartSlop: Dp = from.mouseStartSlop

  internal fun build(): DragPanSettings {
    if (startSlop.isSpecified) requireNonnegativeFinite(startSlop.value.toDouble(), "startSlop")
    requireNonnegativeFinite(mouseStartSlop.value.toDouble(), "mouseStartSlop")
    return DragPanSettings(startSlop, mouseStartSlop)
  }
}

@MapInteractionDsl
public class DragRotatePitchBuilder internal constructor(from: DragRotatePitchSettings) {
  /** Recognition distance for non-mouse pointers, in dp. */
  public var startSlop: Dp = from.startSlop

  /** Recognition distance for mouse pointers, in dp; independent of [startSlop]. */
  public var mouseStartSlop: Dp = from.mouseStartSlop

  /** Point held fixed while rotating and pitching. Defaults to the camera target. */
  public var anchor: GestureAnchor = from.anchor
  public var bearingDegreesPerDp: Double = from.bearingDegreesPerDp
  public var pitchDegreesPerDp: Double = from.pitchDegreesPerDp

  internal fun build(): DragRotatePitchSettings {
    requireNonnegativeFinite(startSlop.value.toDouble(), "startSlop")
    requireNonnegativeFinite(mouseStartSlop.value.toDouble(), "mouseStartSlop")
    require(bearingDegreesPerDp.isFinite()) {
      "bearingDegreesPerDp must be finite, was $bearingDegreesPerDp"
    }
    require(pitchDegreesPerDp.isFinite()) {
      "pitchDegreesPerDp must be finite, was $pitchDegreesPerDp"
    }
    return DragRotatePitchSettings(
      startSlop,
      mouseStartSlop,
      anchor,
      bearingDegreesPerDp,
      pitchDegreesPerDp,
    )
  }
}

@MapInteractionDsl
public class DragFitBoundsBuilder internal constructor(from: DragFitBoundsSettings) {
  /** Recognition distance for non-mouse pointers, in dp. */
  public var startSlop: Dp = from.startSlop

  /** Recognition distance for mouse pointers, in dp; independent of [startSlop]. */
  public var mouseStartSlop: Dp = from.mouseStartSlop

  internal fun build(): DragFitBoundsSettings {
    requireNonnegativeFinite(startSlop.value.toDouble(), "startSlop")
    requireNonnegativeFinite(mouseStartSlop.value.toDouble(), "mouseStartSlop")
    return DragFitBoundsSettings(startSlop, mouseStartSlop)
  }
}

@MapInteractionDsl
public class DragBindingBuilder internal constructor(from: DragBinding) {
  public var enabled: Boolean = from.enabled
  /** Android accessibility gestures may report [PointerType.Unknown]. */
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  private var rows = from.mappings
  private val panBuilder = DragPanBuilder(from.pan)
  private val rotatePitchBuilder = DragRotatePitchBuilder(from.rotatePitch)
  private val fitBoundsBuilder = DragFitBoundsBuilder(from.fitBounds)

  public fun mappings(block: DragMappingsBuilder.() -> Unit) {
    rows = DragMappingsBuilder().apply(block).build()
  }

  public fun pan(block: DragPanBuilder.() -> Unit) {
    panBuilder.apply(block)
  }

  public fun rotatePitch(block: DragRotatePitchBuilder.() -> Unit) {
    rotatePitchBuilder.apply(block)
  }

  public fun fitBounds(block: DragFitBoundsBuilder.() -> Unit) {
    fitBoundsBuilder.apply(block)
  }

  internal fun build(): DragBinding =
    DragBinding(
      enabled,
      pointerTypes?.toSet(),
      rows,
      panBuilder.build(),
      rotatePitchBuilder.build(),
      fitBoundsBuilder.build(),
    )
}

/** Touch-pair panning and pan gestures recognized by the Compose host. */
@MapInteractionDsl
public class TransformPanBuilder internal constructor(from: TransformPanBinding) {
  public var enabled: Boolean = from.enabled
  /** Android accessibility gestures may report [PointerType.Unknown]. */
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  public var modifiers: ModifierMatch? = from.modifiers
  /** Recognition distance for touch pairs. Host-recognized pans have already passed host slop. */
  public var startSlop: Dp = from.startSlop

  internal fun build(): TransformPanBinding {
    requireNonnegativeFinite(startSlop.value.toDouble(), "startSlop")
    return TransformPanBinding(
      enabled,
      pointerTypes?.toSet(),
      modifiers,
      startSlop,
    )
  }
}

@MapInteractionDsl
public class TransformZoomBuilder internal constructor(from: TransformZoomBinding) {
  public var enabled: Boolean = from.enabled
  /** Android accessibility gestures may report [PointerType.Unknown]. */
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  public var modifiers: ModifierMatch? = from.modifiers
  public var startSpanSlop: Dp = from.startSpanSlop
  public var anchor: GestureAnchor = from.anchor
  public var zoomScale: Double = from.zoomScale

  internal fun build(): TransformZoomBinding {
    requireNonnegativeFinite(startSpanSlop.value.toDouble(), "startSpanSlop")
    require(zoomScale.isFinite()) { "zoomScale must be finite, was $zoomScale" }
    return TransformZoomBinding(
      enabled,
      pointerTypes?.toSet(),
      modifiers,
      startSpanSlop,
      anchor,
      zoomScale,
    )
  }
}

@MapInteractionDsl
public class TransformRotateBuilder internal constructor(from: TransformRotateBinding) {
  public var enabled: Boolean = from.enabled
  /** Android accessibility gestures may report [PointerType.Unknown]. */
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  public var modifiers: ModifierMatch? = from.modifiers
  public var startAngle: Double = from.startAngle
  public var anchor: GestureAnchor = from.anchor
  public var rotationScale: Double = from.rotationScale
  public var allowDuringZoom: Boolean = from.allowDuringZoom

  internal fun build(): TransformRotateBinding {
    requireNonnegativeFinite(startAngle, "startAngle")
    require(rotationScale.isFinite()) { "rotationScale must be finite, was $rotationScale" }
    return TransformRotateBinding(
      enabled,
      pointerTypes?.toSet(),
      modifiers,
      startAngle,
      anchor,
      rotationScale,
      allowDuringZoom,
    )
  }
}

@MapInteractionDsl
public class TransformPitchBuilder internal constructor(from: TransformPitchBinding) {
  public var enabled: Boolean = from.enabled
  /** Android accessibility gestures may report [PointerType.Unknown]. */
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  public var modifiers: ModifierMatch? = from.modifiers
  public var startSlop: Dp = from.startSlop
  public var pitchDegreesPerDp: Double = from.pitchDegreesPerDp

  internal fun build(): TransformPitchBinding {
    requireNonnegativeFinite(startSlop.value.toDouble(), "startSlop")
    require(pitchDegreesPerDp.isFinite()) {
      "pitchDegreesPerDp must be finite, was $pitchDegreesPerDp"
    }
    return TransformPitchBinding(
      enabled,
      pointerTypes?.toSet(),
      modifiers,
      startSlop,
      pitchDegreesPerDp,
    )
  }
}

@MapInteractionDsl
public class TapDragBuilder internal constructor(from: TapDragBinding) {
  public var enabled: Boolean = from.enabled
  /** Android accessibility gestures may report [PointerType.Unknown]. */
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  public var modifiers: ModifierMatch? = from.modifiers
  public var startSlop: Dp = from.startSlop
  public var anchor: GestureAnchor = from.anchor

  /**
   * Which vertical drag direction zooms in. The default follows the platform's usual map app:
   * [QuickZoomDirection.UpZoomsIn] on iOS, as in Apple Maps, and [QuickZoomDirection.DownZoomsIn]
   * on Android, the browser, and desktop, including macOS, as in Google Maps.
   */
  public var direction: QuickZoomDirection = from.direction
  public var zoomLevelsPerViewport: Double = from.zoomLevelsPerViewport

  internal fun build(): TapDragBinding {
    requireNonnegativeFinite(startSlop.value.toDouble(), "startSlop")
    require(zoomLevelsPerViewport.isFinite()) {
      "zoomLevelsPerViewport must be finite, was $zoomLevelsPerViewport"
    }
    return TapDragBinding(
      enabled,
      pointerTypes?.toSet(),
      modifiers,
      startSlop,
      anchor,
      direction,
      zoomLevelsPerViewport,
    )
  }
}

/**
 * Two-pointer transforms first cross the Compose host's touch slop. Component thresholds below
 * apply in addition to that initial recognition distance. Host-recognized trackpad gestures have
 * already passed host recognition.
 */
@MapInteractionDsl
public class TransformBuilder internal constructor(from: TransformBinding) {
  private val panBuilder = TransformPanBuilder(from.pan)
  private val zoomBuilder = TransformZoomBuilder(from.zoom)
  private val rotateBuilder = TransformRotateBuilder(from.rotate)
  private val pitchBuilder = TransformPitchBuilder(from.pitch)

  public fun pan(block: TransformPanBuilder.() -> Unit) {
    panBuilder.apply(block)
  }

  public fun zoom(block: TransformZoomBuilder.() -> Unit) {
    zoomBuilder.apply(block)
  }

  public fun rotate(block: TransformRotateBuilder.() -> Unit) {
    rotateBuilder.apply(block)
  }

  public fun pitch(block: TransformPitchBuilder.() -> Unit) {
    pitchBuilder.apply(block)
  }

  internal fun build(): TransformBinding =
    TransformBinding(
      panBuilder.build(),
      zoomBuilder.build(),
      rotateBuilder.build(),
      pitchBuilder.build(),
    )
}

@MapInteractionDsl
public class ScrollBindingBuilder internal constructor(from: ScrollBinding) {
  public var enabled: Boolean = from.enabled
  /** Android accessibility gestures may report [PointerType.Unknown]. */
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  private var rows = from.mappings

  public fun mappings(block: ScrollMappingsBuilder.() -> Unit) {
    rows = ScrollMappingsBuilder().apply(block).build()
  }

  public var idleDuration: Duration = from.idleDuration
  public var anchor: GestureAnchor = from.anchor
  /**
   * Zoom levels per dp of vertical scroll displacement. The default changes zoom by one level per
   * 300 dp. Positive values zoom in when scrolling up; negative values reverse that direction.
   */
  public var zoomPerDp: Double = from.zoomPerDp

  internal fun build(): ScrollBinding {
    requireNonnegativeFinite(idleDuration, "idleDuration")
    require(zoomPerDp.isFinite()) { "zoomPerDp must be finite, was $zoomPerDp" }
    return ScrollBinding(
      enabled,
      pointerTypes?.toSet(),
      rows,
      idleDuration,
      anchor,
      zoomPerDp,
    )
  }
}

@MapInteractionDsl
public class TapBindingBuilder internal constructor(from: TapBinding) {
  public var enabled: Boolean = from.enabled
  /** Android accessibility gestures may report [PointerType.Unknown]. */
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  private var rows = from.mappings

  public fun mappings(block: TapMappingsBuilder.() -> Unit) {
    rows = TapMappingsBuilder().apply(block).build()
  }

  public var anchor: GestureAnchor = from.anchor

  /**
   * Zoom levels that one [CameraAction.ZoomIn] or [CameraAction.ZoomOut] tap changes. One zoom
   * level doubles or halves the map scale. Must be finite; negative values reverse the direction.
   * Building the options throws [IllegalArgumentException] for a value that is not finite.
   */
  public var zoomStepLevels: Double = from.zoomStepLevels

  internal fun build(): TapBinding {
    require(zoomStepLevels.isFinite()) { "zoomStepLevels must be finite, was $zoomStepLevels" }
    return TapBinding(enabled, pointerTypes?.toSet(), rows, anchor, zoomStepLevels)
  }
}

/**
 * Key input and the camera steps that keys take. A press and release of a camera key moves the
 * camera by one step. A held key moves one step per [MapInteractions.animationDuration], or one
 * step per key repeat when that duration is zero.
 *
 * Each step must be finite; negative values reverse the key's direction. Building the options
 * throws [IllegalArgumentException] for a step that is not finite.
 */
@MapInteractionDsl
public class KeyBindingBuilder internal constructor(from: KeyBinding) {
  public var enabled: Boolean = from.enabled
  private var rows = from.mappings

  public fun mappings(block: KeyMappingsBuilder.() -> Unit) {
    rows = KeyMappingsBuilder().apply(block).build()
  }

  /** Screen distance that one step of a pan key moves the map. */
  public var panStep: Dp = from.panStep

  /**
   * Zoom levels that one step of a zoom key changes. One zoom level doubles or halves the map
   * scale.
   */
  public var zoomStepLevels: Double = from.zoomStepLevels

  /** Degrees that one step of a rotate key changes the bearing. */
  public var bearingStepDegrees: Double = from.bearingStepDegrees

  /** Degrees that one step of a pitch key changes the pitch. */
  public var pitchStepDegrees: Double = from.pitchStepDegrees

  internal fun build(): KeyBinding {
    require(panStep.value.isFinite()) { "panStep must be finite, was $panStep" }
    require(zoomStepLevels.isFinite()) { "zoomStepLevels must be finite, was $zoomStepLevels" }
    require(bearingStepDegrees.isFinite()) {
      "bearingStepDegrees must be finite, was $bearingStepDegrees"
    }
    require(pitchStepDegrees.isFinite()) {
      "pitchStepDegrees must be finite, was $pitchStepDegrees"
    }
    return KeyBinding(
      enabled,
      rows,
      panStep,
      zoomStepLevels,
      bearingStepDegrees,
      pitchStepDegrees,
    )
  }
}

/** Zoom from rotary input, such as a watch crown. Only Android reports rotary input. */
@MapInteractionDsl
public class RotaryBindingBuilder internal constructor(from: RotaryBinding) {
  public var enabled: Boolean = from.enabled

  /**
   * Zoom levels that one notch of rotary input changes. One zoom level doubles or halves the map
   * scale. Must be finite; negative values reverse the direction. Building the options throws
   * [IllegalArgumentException] for a value that is not finite.
   */
  public var zoomStepLevels: Double = from.zoomStepLevels
  public var idleDuration: Duration = from.idleDuration

  internal fun build(): RotaryBinding {
    require(zoomStepLevels.isFinite()) { "zoomStepLevels must be finite, was $zoomStepLevels" }
    requireNonnegativeFinite(idleDuration, "idleDuration")
    return RotaryBinding(enabled, zoomStepLevels, idleDuration)
  }
}

/** Input admission, mappings, and source-specific recognition tuning. */
@MapInteractionDsl
public class InteractionBindingsBuilder internal constructor(from: InteractionBindings) {
  private val dragBuilder = DragBindingBuilder(from.drag)
  private val transformBuilder = TransformBuilder(from.transform)
  private val scrollBuilder = ScrollBindingBuilder(from.scroll)
  private val tapBuilder = TapBindingBuilder(from.tap)
  private val doubleTapBuilder = TapBindingBuilder(from.doubleTap)
  private val secondaryClickBuilder = TapBindingBuilder(from.secondaryClick)
  private val longPressBuilder = TapBindingBuilder(from.longPress)
  private val twoFingerTapBuilder = TapBindingBuilder(from.twoFingerTap)
  private val tapDragBuilder = TapDragBuilder(from.tapDrag)
  private val keysBuilder = KeyBindingBuilder(from.keys)
  private val rotaryBuilder = RotaryBindingBuilder(from.rotary)

  public fun drag(block: DragBindingBuilder.() -> Unit) {
    dragBuilder.apply(block)
  }

  public fun transform(block: TransformBuilder.() -> Unit) {
    transformBuilder.apply(block)
  }

  public fun scroll(block: ScrollBindingBuilder.() -> Unit) {
    scrollBuilder.apply(block)
  }

  public fun tap(block: TapBindingBuilder.() -> Unit) {
    tapBuilder.apply(block)
  }

  public fun doubleTap(block: TapBindingBuilder.() -> Unit) {
    doubleTapBuilder.apply(block)
  }

  public fun secondaryClick(block: TapBindingBuilder.() -> Unit) {
    secondaryClickBuilder.apply(block)
  }

  /**
   * Recognizes a held contact after the host's long-press timeout. Independently timed overlay
   * handlers can also recognize the hold before either handler consumes another pointer event.
   */
  public fun longPress(block: TapBindingBuilder.() -> Unit) {
    longPressBuilder.apply(block)
  }

  public fun twoFingerTap(block: TapBindingBuilder.() -> Unit) {
    twoFingerTapBuilder.apply(block)
  }

  public fun tapDrag(block: TapDragBuilder.() -> Unit) {
    tapDragBuilder.apply(block)
  }

  public fun keys(block: KeyBindingBuilder.() -> Unit) {
    keysBuilder.apply(block)
  }

  public fun rotary(block: RotaryBindingBuilder.() -> Unit) {
    rotaryBuilder.apply(block)
  }

  internal fun build(): InteractionBindings =
    InteractionBindings(
      drag = dragBuilder.build(),
      transform = transformBuilder.build(),
      scroll = scrollBuilder.build(),
      tap = tapBuilder.build(),
      doubleTap = doubleTapBuilder.build(),
      secondaryClick = secondaryClickBuilder.build(),
      longPress = longPressBuilder.build(),
      twoFingerTap = twoFingerTapBuilder.build(),
      tapDrag = tapDragBuilder.build(),
      keys = keysBuilder.build(),
      rotary = rotaryBuilder.build(),
    )
}
