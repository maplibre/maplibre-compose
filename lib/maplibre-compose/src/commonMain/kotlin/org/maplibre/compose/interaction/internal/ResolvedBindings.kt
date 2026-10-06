package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.interaction.CameraAction
import org.maplibre.compose.interaction.DragMappingsBuilder
import org.maplibre.compose.interaction.FocusAction
import org.maplibre.compose.interaction.GestureAnchor
import org.maplibre.compose.interaction.KeyMappingsBuilder
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.ModifierMatch
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.compose.interaction.QuickZoomDirection
import org.maplibre.compose.interaction.ScrollMappingsBuilder
import org.maplibre.compose.interaction.TapMappingsBuilder

internal data class DragPanSettings(
  val startSlop: Dp = Dp.Unspecified,
  val mouseStartSlop: Dp = 3.dp,
)

internal data class DragRotatePitchSettings(
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
  val rotatePitch: DragRotatePitchSettings = DragRotatePitchSettings(),
  val fitBounds: DragFitBoundsSettings = DragFitBoundsSettings(),
)

internal data class TransformPanBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val modifiers: ModifierMatch? = null,
  val startSlop: Dp = 4.dp,
)

internal data class TransformZoomBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val modifiers: ModifierMatch? = null,
  val startSpanSlop: Dp = 7.dp,
  val anchor: GestureAnchor = GestureAnchor.Input,
  val zoomScale: Double = 1.0,
)

internal data class TransformRotateBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val modifiers: ModifierMatch? = null,
  val startAngle: Double = 3.0,
  val anchor: GestureAnchor = GestureAnchor.Input,
  val rotationScale: Double = 1.0,
  val allowDuringZoom: Boolean = true,
)

internal data class TransformPitchBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val modifiers: ModifierMatch? = null,
  val startSlop: Dp = 16.dp,
  val pitchDegreesPerDp: Double = -0.3,
)

internal data class TapDragBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val modifiers: ModifierMatch? = null,
  val startSlop: Dp = 7.dp,
  val anchor: GestureAnchor = GestureAnchor.CameraCenter,
  val direction: QuickZoomDirection = platformQuickZoomDirection,
  val zoomLevelsPerViewport: Double = 4.0,
)

internal data class TransformBinding(
  val pan: TransformPanBinding = TransformPanBinding(),
  val zoom: TransformZoomBinding = TransformZoomBinding(),
  val rotate: TransformRotateBinding = TransformRotateBinding(),
  val pitch: TransformPitchBinding = TransformPitchBinding(),
)

internal data class ScrollBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val mappings: List<ScrollMapping> = emptyList(),
  val idleDuration: Duration = 200.milliseconds,
  val anchor: GestureAnchor = GestureAnchor.Input,
  val zoomPerDp: Double = 1.0 / 300.0,
)

internal data class TapBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val mappings: List<TapMapping> = emptyList(),
  val anchor: GestureAnchor = GestureAnchor.Input,
  val zoomStepLevels: Double = 1.0,
)

internal data class KeyBinding(
  val enabled: Boolean = true,
  val mappings: List<KeyMapping> = emptyList(),
  val panStep: Dp = 100.dp,
  val zoomStepLevels: Double = 1.0,
  val bearingStepDegrees: Double = 15.0,
  val pitchStepDegrees: Double = 10.0,
)

internal data class RotaryBinding(
  val enabled: Boolean = true,
  val zoomStepLevels: Double = 0.15,
  val idleDuration: Duration = 200.milliseconds,
)

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
  companion object {
    fun standard(): InteractionBindings {
      val mouse = setOf(PointerType.Mouse)
      val touch =
        setOf(PointerType.Touch, PointerType.Stylus, PointerType.Eraser, PointerType.Unknown)
      return InteractionBindings(
        drag =
          DragBinding(
            mappings =
              DragMappingsBuilder()
                .apply {
                  on(
                    pointerTypes = mouse,
                    button = PointerButton.Secondary,
                    action = CameraAction.RotatePitch,
                  )
                  on(
                    pointerTypes = mouse,
                    button = PointerButton.Primary,
                    modifiers = ModifierMatch.Containing(setOf(KeyModifier.Ctrl)),
                    action = CameraAction.RotatePitch,
                  )
                  on(
                    pointerTypes = mouse,
                    button = PointerButton.Primary,
                    modifiers = ModifierMatch.Containing(setOf(KeyModifier.Shift)),
                    action = CameraAction.FitBounds,
                  )
                  on(button = PointerButton.Primary, action = CameraAction.Pan)
                }
                .build()
          ),
        scroll =
          ScrollBinding(
            mappings =
              ScrollMappingsBuilder()
                .apply {
                  otherwise(CameraAction.Zoom)
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
                    modifiers = ModifierMatch.Containing(setOf(KeyModifier.Shift)),
                    action = CameraAction.ZoomOut,
                  )
                  otherwise(CameraAction.ZoomIn)
                }
                .build()
          ),
        secondaryClick = TapBinding(pointerTypes = mouse),
        // Modifier drags (box zoom, rotate/pitch) keep priority over a paired mouse press.
        tapDrag = TapDragBinding(modifiers = ModifierMatch.Exactly()),
        longPress = TapBinding(pointerTypes = touch),
        twoFingerTap =
          TapBinding(
            mappings = TapMappingsBuilder().apply { otherwise(CameraAction.ZoomOut) }.build()
          ),
        keys =
          KeyBinding(
            mappings =
              KeyMappingsBuilder()
                .apply {
                  on(Key.DirectionLeft, action = CameraAction.PanLeft)
                  on(Key.DirectionRight, action = CameraAction.PanRight)
                  on(Key.DirectionUp, action = CameraAction.PanUp)
                  on(Key.DirectionDown, action = CameraAction.PanDown)
                  on(
                    Key.DirectionLeft,
                    ModifierMatch.Exactly(setOf(KeyModifier.Shift)),
                    action = CameraAction.RotateLeft,
                  )
                  on(
                    Key.DirectionRight,
                    ModifierMatch.Exactly(setOf(KeyModifier.Shift)),
                    action = CameraAction.RotateRight,
                  )
                  on(
                    Key.DirectionUp,
                    ModifierMatch.Exactly(setOf(KeyModifier.Shift)),
                    action = CameraAction.PitchUp,
                  )
                  on(
                    Key.DirectionDown,
                    ModifierMatch.Exactly(setOf(KeyModifier.Shift)),
                    action = CameraAction.PitchDown,
                  )
                  for (key in listOf(Key.Plus, Key.Equals)) {
                    on(key, action = CameraAction.ZoomIn)
                    on(
                      key,
                      ModifierMatch.Exactly(setOf(KeyModifier.Shift)),
                      action = CameraAction.ZoomIn,
                    )
                  }
                  on(Key.Minus, action = CameraAction.ZoomOut)
                  for (key in listOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter)) {
                    on(key, action = FocusAction.Engage)
                  }
                  on(Key.Escape, action = FocusAction.Disengage)
                  on(Key.Back, action = FocusAction.Back)
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
            pitch = TransformPitchBinding(enabled = false),
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
