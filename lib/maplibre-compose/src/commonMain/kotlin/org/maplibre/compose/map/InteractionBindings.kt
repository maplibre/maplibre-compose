package org.maplibre.compose.map

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal data class DragPanSettings(
  val startSlop: Dp = 4.dp,
  val mouseStartSlop: Dp = 3.dp,
)

@MapInteractionDsl
public class DragPanBuilder internal constructor(from: DragPanSettings) {
  /** Recognition distance for non-mouse pointers, in dp. */
  public var startSlop: Dp = from.startSlop

  /** Recognition distance for mouse pointers, in dp; independent of [startSlop]. */
  public var mouseStartSlop: Dp = from.mouseStartSlop

  internal fun build(): DragPanSettings {
    requireNonnegativeFinite(startSlop.value.toDouble(), "startSlop")
    requireNonnegativeFinite(mouseStartSlop.value.toDouble(), "mouseStartSlop")
    return DragPanSettings(startSlop, mouseStartSlop)
  }
}

internal data class DragRotateTiltSettings(
  val startSlop: Dp = 3.dp,
  val mouseStartSlop: Dp = 3.dp,
  val anchor: GestureAnchor = GestureAnchor.CameraCenter,
  val bearingDegreesPerDp: Double = 0.8,
  val pitchDegreesPerDp: Double = -0.5,
)

@MapInteractionDsl
public class DragRotateTiltBuilder internal constructor(from: DragRotateTiltSettings) {
  /** Recognition distance for non-mouse pointers, in dp. */
  public var startSlop: Dp = from.startSlop

  /** Recognition distance for mouse pointers, in dp; independent of [startSlop]. */
  public var mouseStartSlop: Dp = from.mouseStartSlop

  /** Point held fixed while rotating and tilting. Defaults to the camera target. */
  public var anchor: GestureAnchor = from.anchor
  public var bearingDegreesPerDp: Double = from.bearingDegreesPerDp
  public var pitchDegreesPerDp: Double = from.pitchDegreesPerDp

  internal fun build(): DragRotateTiltSettings {
    requireNonnegativeFinite(startSlop.value.toDouble(), "startSlop")
    requireNonnegativeFinite(mouseStartSlop.value.toDouble(), "mouseStartSlop")
    require(bearingDegreesPerDp.isFinite()) { "bearingDegreesPerDp must be finite" }
    require(pitchDegreesPerDp.isFinite()) { "pitchDegreesPerDp must be finite" }
    return DragRotateTiltSettings(
      startSlop,
      mouseStartSlop,
      anchor,
      bearingDegreesPerDp,
      pitchDegreesPerDp,
    )
  }
}

internal data class DragFitBoundsSettings(
  val startSlop: Dp = 3.dp,
  val mouseStartSlop: Dp = 3.dp,
)

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

internal data class CustomDragBinding(
  val key: String,
  val startSlop: Dp = 4.dp,
  val mouseStartSlop: Dp = 3.dp,
  val canStart: (PointerPressEvent) -> Boolean,
  val onEvent: (DragEvent) -> Unit,
) {
  val structuralKey: Any
    get() = listOf(key, startSlop, mouseStartSlop)
}

/** An app-owned drag. The stable key identifies its lifecycle across configuration updates. */
@MapInteractionDsl
public class CustomDragBuilder
internal constructor(private val key: String, from: CustomDragBinding?) {
  /** Recognition distance for non-mouse pointers, in dp. */
  public var startSlop: Dp = from?.startSlop ?: 4.dp

  /** Recognition distance for mouse pointers, in dp; independent of [startSlop]. */
  public var mouseStartSlop: Dp = from?.mouseStartSlop ?: 3.dp
  private var admission = from?.canStart
  private var observer = from?.onEvent

  public fun canStart(block: (PointerPressEvent) -> Boolean) {
    admission = block
  }

  public fun onEvent(block: (DragEvent) -> Unit) {
    observer = block
  }

  internal fun build(): CustomDragBinding {
    requireNonnegativeFinite(startSlop.value.toDouble(), "startSlop")
    requireNonnegativeFinite(mouseStartSlop.value.toDouble(), "mouseStartSlop")
    return CustomDragBinding(
      key,
      startSlop,
      mouseStartSlop,
      requireNotNull(admission) { "custom requires canStart" },
      requireNotNull(observer) { "custom requires onEvent" },
    )
  }
}

internal data class DragBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val mappings: List<DragMapping> = emptyList(),
  val pan: DragPanSettings = DragPanSettings(),
  val rotateTilt: DragRotateTiltSettings = DragRotateTiltSettings(),
  val fitBounds: DragFitBoundsSettings = DragFitBoundsSettings(),
  val custom: List<CustomDragBinding> = emptyList(),
  val handlers: DragHandlers = DragHandlers(),
) {
  val structuralKey: Any
    get() =
      listOf(
        enabled,
        pointerTypes,
        mappings,
        pan,
        rotateTilt,
        fitBounds,
        custom.map { it.structuralKey },
      )
}

@MapInteractionDsl
public class DragBindingBuilder internal constructor(from: DragBinding) {
  public var enabled: Boolean = from.enabled
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  private var rows = from.mappings
  private val panBuilder = DragPanBuilder(from.pan)
  private val rotateTiltBuilder = DragRotateTiltBuilder(from.rotateTilt)
  private val fitBoundsBuilder = DragFitBoundsBuilder(from.fitBounds)
  private val customBindings = from.custom.toMutableList()
  private val declaredKeys = mutableSetOf<String>()

  internal fun beginBlock() {
    declaredKeys.clear()
  }

  private var handlers = from.handlers

  public fun mappings(block: DragMappingsBuilder.() -> Unit) {
    rows = DragMappingsBuilder().apply(block).build()
  }

  public fun pan(block: DragPanBuilder.() -> Unit) {
    panBuilder.apply(block)
  }

  public fun rotateTilt(block: DragRotateTiltBuilder.() -> Unit) {
    rotateTiltBuilder.apply(block)
  }

  public fun fitBounds(block: DragFitBoundsBuilder.() -> Unit) {
    fitBoundsBuilder.apply(block)
  }

  /** Custom candidates precede camera mappings, in declaration order. */
  public fun custom(key: String, block: CustomDragBuilder.() -> Unit) {
    require(key.isNotBlank()) { "A custom drag needs a nonblank key" }
    require(declaredKeys.add(key)) { "Duplicate custom drag key: $key" }
    val index = customBindings.indexOfFirst { it.key == key }
    val updated = CustomDragBuilder(key, customBindings.getOrNull(index)).apply(block).build()
    if (index >= 0) customBindings[index] = updated else customBindings += updated
  }

  /** Removes an inherited custom drag; active instances receive cancellation. */
  public fun removeCustom(key: String) {
    customBindings.removeAll { it.key == key }
  }

  public fun onStart(block: ((DragEvent.Start) -> Unit)?) {
    handlers = handlers.copy(onStart = block)
  }

  public fun onDelta(block: ((DragEvent.Delta) -> Unit)?) {
    handlers = handlers.copy(onDelta = block)
  }

  public fun onEnd(block: ((DragEvent.End) -> Unit)?) {
    handlers = handlers.copy(onEnd = block)
  }

  public fun onCancel(block: ((DragEvent.Cancel) -> Unit)?) {
    handlers = handlers.copy(onCancel = block)
  }

  internal fun build(): DragBinding =
    DragBinding(
      enabled,
      pointerTypes?.toSet(),
      rows,
      panBuilder.build(),
      rotateTiltBuilder.build(),
      fitBoundsBuilder.build(),
      customBindings.toList(),
      handlers,
    )
}

internal data class TransformPanBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val modifiers: ModifierMatch = ModifierMatch.Any,
  val startSlop: Dp = 4.dp,
  val momentumOverride: PanMomentumOverride = PanMomentumOverride(),
  val momentum: PanMomentum = PanMomentum(),
  val handlers: DragHandlers = DragHandlers(),
) {
  val structuralKey: Any
    get() = listOf(enabled, pointerTypes, modifiers, startSlop, momentum)
}

/** Touch-pair panning and pan gestures recognized by the Compose host. */
@MapInteractionDsl
public class TransformPanBuilder internal constructor(from: TransformPanBinding) {
  public var enabled: Boolean = from.enabled
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  public var modifiers: ModifierMatch = from.modifiers
  /** Recognition distance for touch pairs. Host-recognized pans have already passed host slop. */
  public var startSlop: Dp = from.startSlop
  private val momentumBuilder = PanMomentumBuilder(from.momentum, from.momentumOverride)
  private var handlers = from.handlers

  /** Momentum for touch pairs. Host-recognized pans retain only momentum supplied by the host. */
  public fun momentum(block: PanMomentumBuilder.() -> Unit) {
    momentumBuilder.apply(block)
  }

  public fun onStart(block: ((DragEvent.Start) -> Unit)?) {
    handlers = handlers.copy(onStart = block)
  }

  public fun onDelta(block: ((DragEvent.Delta) -> Unit)?) {
    handlers = handlers.copy(onDelta = block)
  }

  public fun onEnd(block: ((DragEvent.End) -> Unit)?) {
    handlers = handlers.copy(onEnd = block)
  }

  public fun onCancel(block: ((DragEvent.Cancel) -> Unit)?) {
    handlers = handlers.copy(onCancel = block)
  }

  internal fun build(base: PanMomentum): TransformPanBinding {
    requireNonnegativeFinite(startSlop.value.toDouble(), "startSlop")
    return TransformPanBinding(
      enabled,
      pointerTypes?.toSet(),
      modifiers,
      startSlop,
      momentumBuilder.overrides,
      momentumBuilder.build(base),
      handlers,
    )
  }
}

internal data class TransformZoomBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val modifiers: ModifierMatch = ModifierMatch.Any,
  val startSpanSlop: Dp = 7.dp,
  val anchor: GestureAnchor = GestureAnchor.Input,
  val zoomScale: Double = 1.0,
  val momentumOverride: VelocityMomentumOverride = VelocityMomentumOverride(),
  val momentum: VelocityMomentum = VelocityMomentum(),
  val handlers: ZoomHandlers = ZoomHandlers(),
) {
  val structuralKey: Any
    get() = listOf(enabled, pointerTypes, modifiers, startSpanSlop, anchor, zoomScale, momentum)
}

@MapInteractionDsl
public class TransformZoomBuilder internal constructor(from: TransformZoomBinding) {
  public var enabled: Boolean = from.enabled
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  public var modifiers: ModifierMatch = from.modifiers
  public var startSpanSlop: Dp = from.startSpanSlop
  public var anchor: GestureAnchor = from.anchor
  public var zoomScale: Double = from.zoomScale
  private val momentumBuilder = VelocityMomentumBuilder(from.momentum, from.momentumOverride)
  private var handlers = from.handlers

  public fun momentum(block: VelocityMomentumBuilder.() -> Unit) {
    momentumBuilder.apply(block)
  }

  public fun onStart(block: ((PinchEvent.Start) -> Unit)?) {
    handlers = handlers.copy(onStart = block)
  }

  public fun onDelta(block: ((PinchEvent.Delta) -> Unit)?) {
    handlers = handlers.copy(onDelta = block)
  }

  public fun onEnd(block: ((PinchEvent.End) -> Unit)?) {
    handlers = handlers.copy(onEnd = block)
  }

  public fun onCancel(block: ((PinchEvent.Cancel) -> Unit)?) {
    handlers = handlers.copy(onCancel = block)
  }

  internal fun build(base: VelocityMomentum): TransformZoomBinding {
    requireNonnegativeFinite(startSpanSlop.value.toDouble(), "startSpanSlop")
    require(zoomScale.isFinite()) { "zoomScale must be finite" }
    return TransformZoomBinding(
      enabled,
      pointerTypes?.toSet(),
      modifiers,
      startSpanSlop,
      anchor,
      zoomScale,
      momentumBuilder.overrides,
      momentumBuilder.build(base),
      handlers,
    )
  }
}

internal data class TransformRotateBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val modifiers: ModifierMatch = ModifierMatch.Any,
  val startAngle: Double = 3.0,
  val anchor: GestureAnchor = GestureAnchor.Input,
  val rotationScale: Double = 1.0,
  val allowDuringZoom: Boolean = true,
  val momentumOverride: VelocityMomentumOverride = VelocityMomentumOverride(),
  val momentum: VelocityMomentum = VelocityMomentum(),
  val handlers: RotateHandlers = RotateHandlers(),
) {
  val structuralKey: Any
    get() =
      listOf(
        enabled,
        pointerTypes,
        modifiers,
        startAngle,
        anchor,
        rotationScale,
        allowDuringZoom,
        momentum,
      )
}

@MapInteractionDsl
public class TransformRotateBuilder internal constructor(from: TransformRotateBinding) {
  public var enabled: Boolean = from.enabled
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  public var modifiers: ModifierMatch = from.modifiers
  public var startAngle: Double = from.startAngle
  public var anchor: GestureAnchor = from.anchor
  public var rotationScale: Double = from.rotationScale
  public var allowDuringZoom: Boolean = from.allowDuringZoom
  private val momentumBuilder = VelocityMomentumBuilder(from.momentum, from.momentumOverride)
  private var handlers = from.handlers

  public fun momentum(block: VelocityMomentumBuilder.() -> Unit) {
    momentumBuilder.apply(block)
  }

  public fun onStart(block: ((RotateEvent.Start) -> Unit)?) {
    handlers = handlers.copy(onStart = block)
  }

  public fun onDelta(block: ((RotateEvent.Delta) -> Unit)?) {
    handlers = handlers.copy(onDelta = block)
  }

  public fun onEnd(block: ((RotateEvent.End) -> Unit)?) {
    handlers = handlers.copy(onEnd = block)
  }

  public fun onCancel(block: ((RotateEvent.Cancel) -> Unit)?) {
    handlers = handlers.copy(onCancel = block)
  }

  internal fun build(base: VelocityMomentum): TransformRotateBinding {
    requireNonnegativeFinite(startAngle, "startAngle")
    require(rotationScale.isFinite()) { "rotationScale must be finite" }
    return TransformRotateBinding(
      enabled,
      pointerTypes?.toSet(),
      modifiers,
      startAngle,
      anchor,
      rotationScale,
      allowDuringZoom,
      momentumBuilder.overrides,
      momentumBuilder.build(base),
      handlers,
    )
  }
}

internal data class TransformTiltBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val modifiers: ModifierMatch = ModifierMatch.Any,
  val startSlop: Dp = 16.dp,
  val pitchDegreesPerDp: Double = -0.1,
  val momentumOverride: TiltMomentumOverride = TiltMomentumOverride(),
  val momentum: TiltMomentum = TiltMomentum(),
  val handlers: TiltHandlers = TiltHandlers(),
) {
  val structuralKey: Any
    get() = listOf(enabled, pointerTypes, modifiers, startSlop, pitchDegreesPerDp, momentum)
}

@MapInteractionDsl
public class TransformTiltBuilder internal constructor(from: TransformTiltBinding) {
  public var enabled: Boolean = from.enabled
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  public var modifiers: ModifierMatch = from.modifiers
  public var startSlop: Dp = from.startSlop
  public var pitchDegreesPerDp: Double = from.pitchDegreesPerDp
  private val momentumBuilder = TiltMomentumBuilder(from.momentum, from.momentumOverride)
  private var handlers = from.handlers

  public fun momentum(block: TiltMomentumBuilder.() -> Unit) {
    momentumBuilder.apply(block)
  }

  public fun onStart(block: ((ShoveEvent.Start) -> Unit)?) {
    handlers = handlers.copy(onStart = block)
  }

  public fun onDelta(block: ((ShoveEvent.Delta) -> Unit)?) {
    handlers = handlers.copy(onDelta = block)
  }

  public fun onEnd(block: ((ShoveEvent.End) -> Unit)?) {
    handlers = handlers.copy(onEnd = block)
  }

  public fun onCancel(block: ((ShoveEvent.Cancel) -> Unit)?) {
    handlers = handlers.copy(onCancel = block)
  }

  internal fun build(base: TiltMomentum): TransformTiltBinding {
    requireNonnegativeFinite(startSlop.value.toDouble(), "startSlop")
    require(pitchDegreesPerDp.isFinite()) { "pitchDegreesPerDp must be finite" }
    return TransformTiltBinding(
      enabled,
      pointerTypes?.toSet(),
      modifiers,
      startSlop,
      pitchDegreesPerDp,
      momentumBuilder.overrides,
      momentumBuilder.build(base),
      handlers,
    )
  }
}

internal data class TapDragBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? =
    setOf(PointerType.Touch, PointerType.Stylus, PointerType.Eraser),
  val modifiers: ModifierMatch = ModifierMatch.Any,
  val startSlop: Dp = 7.dp,
  val anchor: GestureAnchor = GestureAnchor.CameraCenter,
  val direction: QuickZoomDirection = QuickZoomDirection.DownZoomsIn,
  val zoomLevelsPerViewport: Double = 4.0,
  val momentumOverride: VelocityMomentumOverride = VelocityMomentumOverride(),
  val momentum: VelocityMomentum = VelocityMomentum(),
  val handlers: DragHandlers = DragHandlers(),
) {
  val structuralKey: Any
    get() =
      listOf(
        enabled,
        pointerTypes,
        modifiers,
        startSlop,
        anchor,
        direction,
        zoomLevelsPerViewport,
        momentum,
      )
}

@MapInteractionDsl
public class TapDragBuilder internal constructor(from: TapDragBinding) {
  public var enabled: Boolean = from.enabled
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  public var modifiers: ModifierMatch = from.modifiers
  public var startSlop: Dp = from.startSlop
  public var anchor: GestureAnchor = from.anchor
  public var direction: QuickZoomDirection = from.direction
  public var zoomLevelsPerViewport: Double = from.zoomLevelsPerViewport
  private val momentumBuilder = VelocityMomentumBuilder(from.momentum, from.momentumOverride)
  private var handlers = from.handlers

  public fun momentum(block: VelocityMomentumBuilder.() -> Unit) {
    momentumBuilder.apply(block)
  }

  public fun onStart(block: ((DragEvent.Start) -> Unit)?) {
    handlers = handlers.copy(onStart = block)
  }

  public fun onDelta(block: ((DragEvent.Delta) -> Unit)?) {
    handlers = handlers.copy(onDelta = block)
  }

  public fun onEnd(block: ((DragEvent.End) -> Unit)?) {
    handlers = handlers.copy(onEnd = block)
  }

  public fun onCancel(block: ((DragEvent.Cancel) -> Unit)?) {
    handlers = handlers.copy(onCancel = block)
  }

  internal fun build(base: VelocityMomentum): TapDragBinding {
    requireNonnegativeFinite(startSlop.value.toDouble(), "startSlop")
    require(zoomLevelsPerViewport.isFinite()) { "zoomLevelsPerViewport must be finite" }
    return TapDragBinding(
      enabled,
      pointerTypes?.toSet(),
      modifiers,
      startSlop,
      anchor,
      direction,
      zoomLevelsPerViewport,
      momentumBuilder.overrides,
      momentumBuilder.build(base),
      handlers,
    )
  }
}

internal data class TransformBinding(
  val pan: TransformPanBinding = TransformPanBinding(),
  val zoom: TransformZoomBinding = TransformZoomBinding(),
  val rotate: TransformRotateBinding = TransformRotateBinding(),
  val tilt: TransformTiltBinding = TransformTiltBinding(),
) {
  val structuralKey: Any
    get() = listOf(pan.structuralKey, zoom.structuralKey, rotate.structuralKey, tilt.structuralKey)
}

@MapInteractionDsl
public class TransformBuilder internal constructor(from: TransformBinding) {
  private val panBuilder = TransformPanBuilder(from.pan)
  private val zoomBuilder = TransformZoomBuilder(from.zoom)
  private val rotateBuilder = TransformRotateBuilder(from.rotate)
  private val tiltBuilder = TransformTiltBuilder(from.tilt)

  public fun pan(block: TransformPanBuilder.() -> Unit) {
    panBuilder.apply(block)
  }

  public fun zoom(block: TransformZoomBuilder.() -> Unit) {
    zoomBuilder.apply(block)
  }

  public fun rotate(block: TransformRotateBuilder.() -> Unit) {
    rotateBuilder.apply(block)
  }

  public fun tilt(block: TransformTiltBuilder.() -> Unit) {
    tiltBuilder.apply(block)
  }

  internal fun build(camera: CameraConfiguration): TransformBinding =
    TransformBinding(
      panBuilder.build(camera.pan.momentum),
      zoomBuilder.build(camera.zoom.momentum),
      rotateBuilder.build(camera.rotate.momentum),
      tiltBuilder.build(camera.tilt.momentum),
    )
}

internal data class ScrollBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val mappings: List<ScrollMapping> = emptyList(),
  val idleDuration: Duration = 200.milliseconds,
  val anchor: GestureAnchor = GestureAnchor.Input,
  val zoomStep: Double = 0.15,
  val handlers: ScrollHandlers = ScrollHandlers(),
) {
  val structuralKey: Any
    get() = listOf(enabled, pointerTypes, mappings, idleDuration, anchor, zoomStep)
}

@MapInteractionDsl
public class ScrollBindingBuilder internal constructor(from: ScrollBinding) {
  public var enabled: Boolean = from.enabled
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  private var rows = from.mappings

  public fun mappings(block: ScrollMappingsBuilder.() -> Unit) {
    rows = ScrollMappingsBuilder().apply(block).build()
  }

  public var idleDuration: Duration = from.idleDuration
  public var anchor: GestureAnchor = from.anchor
  public var zoomStep: Double = from.zoomStep
  private var handlers = from.handlers

  public fun onStart(block: ((ScrollEvent.Start) -> Unit)?) {
    handlers = handlers.copy(onStart = block)
  }

  public fun onDelta(block: ((ScrollEvent.Delta) -> Unit)?) {
    handlers = handlers.copy(onDelta = block)
  }

  public fun onEnd(block: ((ScrollEvent.End) -> Unit)?) {
    handlers = handlers.copy(onEnd = block)
  }

  public fun onCancel(block: ((ScrollEvent.Cancel) -> Unit)?) {
    handlers = handlers.copy(onCancel = block)
  }

  internal fun build(): ScrollBinding {
    requireNonnegativeFinite(idleDuration, "idleDuration")
    require(zoomStep.isFinite()) { "zoomStep must be finite" }
    return ScrollBinding(
      enabled,
      pointerTypes?.toSet(),
      rows,
      idleDuration,
      anchor,
      zoomStep,
      handlers,
    )
  }
}

internal data class TapBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val mappings: List<TapMapping> = emptyList(),
  val anchor: GestureAnchor = GestureAnchor.Input,
  val zoomStep: Double = 1.0,
) {
  val structuralKey: Any
    get() = listOf(enabled, pointerTypes, mappings, anchor, zoomStep)
}

@MapInteractionDsl
public class TapBindingBuilder internal constructor(from: TapBinding) {
  public var enabled: Boolean = from.enabled
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  private var rows = from.mappings

  public fun mappings(block: TapMappingsBuilder.() -> Unit) {
    rows = TapMappingsBuilder().apply(block).build()
  }

  public var anchor: GestureAnchor = from.anchor
  public var zoomStep: Double = from.zoomStep

  internal fun build(): TapBinding {
    require(zoomStep.isFinite()) { "zoomStep must be finite" }
    return TapBinding(enabled, pointerTypes?.toSet(), rows, anchor, zoomStep)
  }
}

internal data class HoverBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? =
    setOf(PointerType.Mouse, PointerType.Stylus, PointerType.Eraser),
  val modifiers: ModifierMatch = ModifierMatch.Any,
) {
  val structuralKey: Any
    get() = listOf(enabled, pointerTypes, modifiers)
}

@MapInteractionDsl
public class HoverBindingBuilder internal constructor(from: HoverBinding) {
  public var enabled: Boolean = from.enabled
  public var pointerTypes: Set<PointerType>? = from.pointerTypes
  public var modifiers: ModifierMatch = from.modifiers

  internal fun build(): HoverBinding {
    return HoverBinding(enabled, pointerTypes?.toSet(), modifiers)
  }
}

internal data class KeyBinding(
  val enabled: Boolean = true,
  val mappings: List<KeyMapping> = emptyList(),
  val panStep: Dp = 100.dp,
  val zoomStep: Double = 1.0,
  val rotateStep: Double = 15.0,
  val pitchStep: Double = 10.0,
  val onEvent: ((KeyGestureEvent) -> Unit)? = null,
) {
  val structuralKey: Any
    get() = listOf(enabled, mappings, panStep, zoomStep, rotateStep, pitchStep)
}

@MapInteractionDsl
public class KeyBindingBuilder internal constructor(from: KeyBinding) {
  public var enabled: Boolean = from.enabled
  private var rows = from.mappings

  public fun mappings(block: KeyMappingsBuilder.() -> Unit) {
    rows = KeyMappingsBuilder().apply(block).build()
  }

  public var panStep: Dp = from.panStep
  public var zoomStep: Double = from.zoomStep
  public var rotateStep: Double = from.rotateStep
  public var pitchStep: Double = from.pitchStep
  private var observer = from.onEvent

  public fun onEvent(block: ((KeyGestureEvent) -> Unit)?) {
    observer = block
  }

  internal fun build(): KeyBinding {
    require(panStep.value.isFinite()) { "panStep must be finite" }
    require(zoomStep.isFinite()) { "zoomStep must be finite" }
    require(rotateStep.isFinite()) { "rotateStep must be finite" }
    require(pitchStep.isFinite()) { "pitchStep must be finite" }
    return KeyBinding(enabled, rows, panStep, zoomStep, rotateStep, pitchStep, observer)
  }
}

internal data class RotaryBinding(
  val enabled: Boolean = true,
  val zoomStep: Double = 0.15,
  val idleDuration: Duration = 200.milliseconds,
  val onEvent: ((RotaryGestureEvent) -> Unit)? = null,
) {
  val structuralKey: Any
    get() = listOf(enabled, zoomStep, idleDuration)
}

@MapInteractionDsl
public class RotaryBindingBuilder internal constructor(from: RotaryBinding) {
  public var enabled: Boolean = from.enabled
  public var zoomStep: Double = from.zoomStep
  public var idleDuration: Duration = from.idleDuration
  private var observer = from.onEvent

  public fun onEvent(block: ((RotaryGestureEvent) -> Unit)?) {
    observer = block
  }

  internal fun build(): RotaryBinding {
    require(zoomStep.isFinite()) { "zoomStep must be finite" }
    requireNonnegativeFinite(idleDuration, "idleDuration")
    return RotaryBinding(enabled, zoomStep, idleDuration, observer)
  }
}

internal data class InteractionBindings(
  val drag: DragBinding = DragBinding(),
  val transform: TransformBinding = TransformBinding(),
  val scroll: ScrollBinding = ScrollBinding(),
  val tap: TapBinding = TapBinding(),
  val doubleTap: TapBinding = TapBinding(),
  val secondaryClick: TapBinding = TapBinding(),
  val longPress: TapBinding = TapBinding(),
  val twoFingerTap: TapBinding = TapBinding(),
  val tapDrag: TapDragBinding = TapDragBinding(),
  val hover: HoverBinding = HoverBinding(),
  val keys: KeyBinding = KeyBinding(),
  val rotary: RotaryBinding = RotaryBinding(),
) {
  val structuralKey: Any
    get() =
      listOf(
        drag.structuralKey,
        transform.structuralKey,
        scroll.structuralKey,
        tap.structuralKey,
        doubleTap.structuralKey,
        secondaryClick.structuralKey,
        longPress.structuralKey,
        twoFingerTap.structuralKey,
        tapDrag.structuralKey,
        hover.structuralKey,
        keys.structuralKey,
        rotary.structuralKey,
      )

  companion object {
    fun standard(): InteractionBindings {
      val mouse = setOf(PointerType.Mouse)
      val touch = setOf(PointerType.Touch, PointerType.Stylus, PointerType.Eraser)
      return InteractionBindings(
        drag =
          DragBinding(
            mappings =
              DragMappingsBuilder()
                .apply {
                  on(pointerTypes = mouse, button = PointerButton.Secondary) { rotateTilt() }
                  on(
                    pointerTypes = mouse,
                    button = PointerButton.Primary,
                    modifiers = ModifierMatch.Containing(KeyModifier.Ctrl),
                  ) {
                    rotateTilt()
                  }
                  on(
                    pointerTypes = mouse,
                    button = PointerButton.Primary,
                    modifiers = ModifierMatch.Containing(KeyModifier.Shift),
                  ) {
                    fitBounds()
                  }
                  on(button = PointerButton.Primary) { pan() }
                }
                .build()
          ),
        scroll =
          ScrollBinding(
            mappings =
              ScrollMappingsBuilder()
                .apply {
                  otherwise { zoom() }
                }
                .build()
          ),
        doubleTap =
          TapBinding(
            mappings =
              TapMappingsBuilder()
                .apply {
                  on(
                    pointerTypes = mouse,
                    modifiers = ModifierMatch.Containing(KeyModifier.Shift),
                  ) {
                    zoomOut()
                  }
                  otherwise { zoomIn() }
                }
                .build()
          ),
        secondaryClick = TapBinding(pointerTypes = mouse),
        longPress = TapBinding(pointerTypes = touch),
        twoFingerTap =
          TapBinding(mappings = TapMappingsBuilder().apply { otherwise { zoomOut() } }.build()),
        keys =
          KeyBinding(
            mappings =
              KeyMappingsBuilder()
                .apply {
                  on(Key.DirectionLeft) { panLeft() }
                  on(Key.DirectionRight) { panRight() }
                  on(Key.DirectionUp) { panUp() }
                  on(Key.DirectionDown) { panDown() }
                  on(Key.DirectionLeft, ModifierMatch.Exactly(KeyModifier.Shift)) { rotateLeft() }
                  on(Key.DirectionRight, ModifierMatch.Exactly(KeyModifier.Shift)) { rotateRight() }
                  on(Key.DirectionUp, ModifierMatch.Exactly(KeyModifier.Shift)) { tiltUp() }
                  on(Key.DirectionDown, ModifierMatch.Exactly(KeyModifier.Shift)) { tiltDown() }
                  for (key in listOf(Key.Plus, Key.Equals)) {
                    on(key) { zoomIn() }
                    on(key, ModifierMatch.Exactly(KeyModifier.Shift)) { zoomIn() }
                  }
                  on(Key.Minus) { zoomOut() }
                  for (key in listOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter)) {
                    on(key) { engage() }
                  }
                  on(Key.Escape) { disengage() }
                  on(Key.Back) { back() }
                }
                .build()
          ),
      )
    }

    fun none(): InteractionBindings =
      InteractionBindings(
        drag = DragBinding(enabled = false),
        transform =
          TransformBinding(
            pan = TransformPanBinding(enabled = false),
            zoom = TransformZoomBinding(enabled = false),
            rotate = TransformRotateBinding(enabled = false),
            tilt = TransformTiltBinding(enabled = false),
          ),
        scroll = ScrollBinding(enabled = false),
        tap = TapBinding(enabled = false),
        doubleTap = TapBinding(enabled = false),
        secondaryClick = TapBinding(enabled = false),
        longPress = TapBinding(enabled = false),
        twoFingerTap = TapBinding(enabled = false),
        tapDrag = TapDragBinding(enabled = false),
        hover = HoverBinding(enabled = false),
        keys = KeyBinding(enabled = false),
        rotary = RotaryBinding(enabled = false),
      )
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
  private val hoverBuilder = HoverBindingBuilder(from.hover)
  private val keysBuilder = KeyBindingBuilder(from.keys)
  private val rotaryBuilder = RotaryBindingBuilder(from.rotary)

  public fun drag(block: DragBindingBuilder.() -> Unit) {
    dragBuilder.beginBlock()
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

  public fun longPress(block: TapBindingBuilder.() -> Unit) {
    longPressBuilder.apply(block)
  }

  public fun twoFingerTap(block: TapBindingBuilder.() -> Unit) {
    twoFingerTapBuilder.apply(block)
  }

  public fun tapDrag(block: TapDragBuilder.() -> Unit) {
    tapDragBuilder.apply(block)
  }

  public fun hover(block: HoverBindingBuilder.() -> Unit) {
    hoverBuilder.apply(block)
  }

  public fun keys(block: KeyBindingBuilder.() -> Unit) {
    keysBuilder.apply(block)
  }

  public fun rotary(block: RotaryBindingBuilder.() -> Unit) {
    rotaryBuilder.apply(block)
  }

  internal fun build(camera: CameraConfiguration): InteractionBindings =
    InteractionBindings(
      drag = dragBuilder.build(),
      transform = transformBuilder.build(camera),
      scroll = scrollBuilder.build(),
      tap = tapBuilder.build(),
      doubleTap = doubleTapBuilder.build(),
      secondaryClick = secondaryClickBuilder.build(),
      longPress = longPressBuilder.build(),
      twoFingerTap = twoFingerTapBuilder.build(),
      tapDrag = tapDragBuilder.build(camera.zoom.momentum),
      hover = hoverBuilder.build(),
      keys = keysBuilder.build(),
      rotary = rotaryBuilder.build(),
    )
}
