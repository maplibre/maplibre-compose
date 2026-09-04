package org.maplibre.compose.material3

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import org.maplibre.compose.overlay.FocusRing as BaseFocusRing
import org.maplibre.compose.overlay.FocusRingDefaults
import org.maplibre.compose.overlay.FocusRingStyle
import org.maplibre.compose.overlay.MapOverlayScope

/**
 * A border around the map that shows while the map holds keyboard focus. It covers the full map,
 * including the region that [MapOverlayScope.contentWindowInsets] obstructs.
 *
 * This is [org.maplibre.compose.overlay.FocusRing] drawn in the primary color of the Material 3
 * color scheme.
 *
 * @param color Color of the stroke.
 * @param strokeWidth Stroke width while the map is focused and not engaged.
 * @param engagedStrokeWidth Stroke width while the map is engaged.
 * @param shape Shape of the ring.
 */
@Composable
public fun MapOverlayScope.FocusRing(
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.primary,
  strokeWidth: Dp = FocusRingDefaults.StrokeWidth,
  engagedStrokeWidth: Dp = FocusRingDefaults.EngagedStrokeWidth,
  shape: Shape = FocusRingDefaults.Shape,
) {
  BaseFocusRing(
    modifier = modifier,
    style =
      FocusRingStyle(
        color = color,
        strokeWidth = strokeWidth,
        engagedStrokeWidth = engagedStrokeWidth,
        shape = shape,
      ),
  )
}
