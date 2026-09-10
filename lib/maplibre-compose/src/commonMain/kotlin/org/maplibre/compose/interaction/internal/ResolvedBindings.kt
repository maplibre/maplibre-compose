package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.interaction.DragMappingsBuilder
import org.maplibre.compose.interaction.DragResponse
import org.maplibre.compose.interaction.GestureAnchor
import org.maplibre.compose.interaction.KeyMappingsBuilder
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.KeyResponse
import org.maplibre.compose.interaction.ModifierMatch
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.compose.interaction.QuickZoomDirection
import org.maplibre.compose.interaction.ScrollMappingsBuilder
import org.maplibre.compose.interaction.ScrollResponse
import org.maplibre.compose.interaction.TapMappingsBuilder
import org.maplibre.compose.interaction.TapResponse

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

internal data class TransformTiltBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? = null,
  val modifiers: ModifierMatch? = null,
  val startSlop: Dp = 16.dp,
  val pitchDegreesPerDp: Double = -0.1,
)

internal data class TapDragBinding(
  val enabled: Boolean = true,
  val pointerTypes: Set<PointerType>? =
    setOf(PointerType.Touch, PointerType.Stylus, PointerType.Eraser, PointerType.Unknown),
  val modifiers: ModifierMatch? = null,
  val startSlop: Dp = 7.dp,
  val anchor: GestureAnchor = GestureAnchor.CameraCenter,
  val direction: QuickZoomDirection = QuickZoomDirection.DownZoomsIn,
  val zoomLevelsPerViewport: Double = 4.0,
)

internal data class TransformBinding(
  val pan: TransformPanBinding = TransformPanBinding(),
  val zoom: TransformZoomBinding = TransformZoomBinding(),
  val rotate: TransformRotateBinding = TransformRotateBinding(),
  val tilt: TransformTiltBinding = TransformTiltBinding(),
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
  val zoomStep: Double = 1.0,
)

internal data class KeyBinding(
  val enabled: Boolean = true,
  val mappings: List<KeyMapping> = emptyList(),
  val panStep: Dp = 100.dp,
  val zoomStep: Double = 1.0,
  val rotateStep: Double = 15.0,
  val pitchStep: Double = 10.0,
)

internal data class RotaryBinding(
  val enabled: Boolean = true,
  val zoomStep: Double = 0.15,
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
                    response = DragResponse.RotateTilt,
                  )
                  on(
                    pointerTypes = mouse,
                    button = PointerButton.Primary,
                    modifiers = ModifierMatch.Containing(KeyModifier.Ctrl),
                    response = DragResponse.RotateTilt,
                  )
                  on(
                    pointerTypes = mouse,
                    button = PointerButton.Primary,
                    modifiers = ModifierMatch.Containing(KeyModifier.Shift),
                    response = DragResponse.FitBounds,
                  )
                  on(button = PointerButton.Primary, response = DragResponse.Pan)
                }
                .build()
          ),
        scroll =
          ScrollBinding(
            mappings =
              ScrollMappingsBuilder()
                .apply {
                  otherwise(ScrollResponse.Zoom)
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
                    response = TapResponse.ZoomOut,
                  )
                  otherwise(TapResponse.ZoomIn)
                }
                .build()
          ),
        secondaryClick = TapBinding(pointerTypes = mouse),
        longPress = TapBinding(pointerTypes = touch),
        twoFingerTap =
          TapBinding(
            mappings = TapMappingsBuilder().apply { otherwise(TapResponse.ZoomOut) }.build()
          ),
        keys =
          KeyBinding(
            mappings =
              KeyMappingsBuilder()
                .apply {
                  on(Key.DirectionLeft, response = KeyResponse.PanLeft)
                  on(Key.DirectionRight, response = KeyResponse.PanRight)
                  on(Key.DirectionUp, response = KeyResponse.PanUp)
                  on(Key.DirectionDown, response = KeyResponse.PanDown)
                  on(
                    Key.DirectionLeft,
                    ModifierMatch.Exactly(KeyModifier.Shift),
                    response = KeyResponse.RotateLeft,
                  )
                  on(
                    Key.DirectionRight,
                    ModifierMatch.Exactly(KeyModifier.Shift),
                    response = KeyResponse.RotateRight,
                  )
                  on(
                    Key.DirectionUp,
                    ModifierMatch.Exactly(KeyModifier.Shift),
                    response = KeyResponse.TiltUp,
                  )
                  on(
                    Key.DirectionDown,
                    ModifierMatch.Exactly(KeyModifier.Shift),
                    response = KeyResponse.TiltDown,
                  )
                  for (key in listOf(Key.Plus, Key.Equals)) {
                    on(key, response = KeyResponse.ZoomIn)
                    on(key, ModifierMatch.Exactly(KeyModifier.Shift), response = KeyResponse.ZoomIn)
                  }
                  on(Key.Minus, response = KeyResponse.ZoomOut)
                  for (key in listOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter)) {
                    on(key, response = KeyResponse.Engage)
                  }
                  on(Key.Escape, response = KeyResponse.Disengage)
                  on(Key.Back, response = KeyResponse.Back)
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
