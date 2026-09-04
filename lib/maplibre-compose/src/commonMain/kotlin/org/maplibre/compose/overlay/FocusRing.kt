package org.maplibre.compose.overlay

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A border around the map that shows while the map holds keyboard focus. It covers the full map,
 * including the region that [MapOverlayScope.contentWindowInsets] obstructs.
 *
 * The ring appears while [MapState.isFocused][org.maplibre.compose.map.MapState.isFocused] is true
 * and [LocalInputModeManager] reports [InputMode.Keyboard]. It draws a thicker stroke while
 * [MapState.isEngaged][org.maplibre.compose.map.MapState.isEngaged] is true. A touch or pointer
 * user never sees it.
 *
 * The ring is a drawing only. It takes no focus and handles no pointer input, so a press on it
 * reaches the map.
 *
 * This component draws with Compose Foundation alone. The Material 3 module provides a themed
 * version of it.
 *
 * @param style Color, stroke widths, and shape of the ring.
 */
@Composable
public fun MapOverlayScope.FocusRing(
  modifier: Modifier = Modifier,
  style: FocusRingStyle = FocusRingDefaults.style(),
) {
  val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
  if (!keyboardMode || !mapState.isFocused) return
  val strokeWidth = if (mapState.isEngaged) style.engagedStrokeWidth else style.strokeWidth
  Box(modifier.fillOverlay().fillMaxSize().border(strokeWidth, style.color, style.shape))
}

public object FocusRingDefaults {
  /** Contrasts with light basemaps, in the absence of a theme to draw colors from. */
  public val StrokeColor: Color = Color.Black.copy(alpha = 0.75f)

  /** Stroke width while the map is focused and not engaged. */
  public val StrokeWidth: Dp = 2.dp

  /** Stroke width while the map is engaged. */
  public val EngagedStrokeWidth: Dp = 4.dp

  /** Shape of the ring. */
  public val Shape: Shape = RectangleShape

  /** The style that [FocusRing] uses when the caller passes none. */
  public fun style(): FocusRingStyle = FocusRingStyle()
}

@Immutable
public data class FocusRingStyle(
  /** Color of the stroke. */
  public val color: Color = FocusRingDefaults.StrokeColor,

  /** Stroke width while the map is focused and not engaged. */
  public val strokeWidth: Dp = FocusRingDefaults.StrokeWidth,

  /** Stroke width while the map is engaged. */
  public val engagedStrokeWidth: Dp = FocusRingDefaults.EngagedStrokeWidth,

  /** Shape of the ring. */
  public val shape: Shape = FocusRingDefaults.Shape,
)
