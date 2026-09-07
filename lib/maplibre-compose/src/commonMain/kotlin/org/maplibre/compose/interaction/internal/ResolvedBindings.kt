package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.interaction.DragMappingsBuilder
import org.maplibre.compose.interaction.GestureAnchor
import org.maplibre.compose.interaction.KeyGestureEvent
import org.maplibre.compose.interaction.KeyMappingsBuilder
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.ModifierMatch
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.compose.interaction.QuickZoomDirection
import org.maplibre.compose.interaction.RotaryGestureEvent
import org.maplibre.compose.interaction.ScrollMappingsBuilder
import org.maplibre.compose.interaction.TapMappingsBuilder

internal data class DragPanSettings(
  val startSlop: Dp = 4.dp,
  val mouseStartSlop: Dp = 3.dp,
)

internal data class DragRotateTiltSettings(
  val startSlop: Dp = 3.dp,
  val mouseStartSlop: Dp = 3.dp,
  val anchor: GestureAnchor = GestureAnchor.CameraCenter,
  val bearingDegreesPerDp: Double = 0.8,
  val pitchDegreesPerDp: Double = -0.5,
)

internal data class DragFitBoundsSettings(
  val startSlop: Dp = 3.dp,
  val mouseStartSlop: Dp = 3.dp,
)

internal data class DragBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val mappings: List<DragMapping> = emptyList(),
  val pan: DragPanSettings = DragPanSettings(),
  val rotateTilt: DragRotateTiltSettings = DragRotateTiltSettings(),
  val fitBounds: DragFitBoundsSettings = DragFitBoundsSettings(),
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

internal data class TransformBinding(
  val pan: TransformPanBinding = TransformPanBinding(),
  val zoom: TransformZoomBinding = TransformZoomBinding(),
  val rotate: TransformRotateBinding = TransformRotateBinding(),
  val tilt: TransformTiltBinding = TransformTiltBinding(),
) {
  val structuralKey: Any
    get() = listOf(pan.structuralKey, zoom.structuralKey, rotate.structuralKey, tilt.structuralKey)
}

internal data class ScrollBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val mappings: List<ScrollMapping> = emptyList(),
  val idleDuration: Duration = 200.milliseconds,
  val anchor: GestureAnchor = GestureAnchor.Input,
  val zoomPerDp: Double = 0.0015,
  val handlers: ScrollHandlers = ScrollHandlers(),
) {
  val structuralKey: Any
    get() = listOf(enabled, pointerTypes, mappings, idleDuration, anchor, zoomPerDp)
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

internal data class RotaryBinding(
  val enabled: Boolean = true,
  val zoomStep: Double = 0.15,
  val idleDuration: Duration = 200.milliseconds,
  val onEvent: ((RotaryGestureEvent) -> Unit)? = null,
) {
  val structuralKey: Any
    get() = listOf(enabled, zoomStep, idleDuration)
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
        keys = KeyBinding(enabled = false),
        rotary = RotaryBinding(enabled = false),
      )
  }
}
