package org.maplibre.compose.location

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Sizing parameters for [LocationPuck]
 *
 * @param dotRadius the radius of the main location indicator dot
 * @param dotStrokeWidth the stroke width for the border of the main indicator dot
 * @param shadowSize if positive, a shadow will be drawn underneath the main indicator dot with a
 *   radius of `dotRadius + dotStrokeWidth + shadowSize`, i.e. the shadow extends [shadowSize]
 *   beyond the dot
 * @param shadowOffset an offset applied to the shadow of the main indicator dot
 * @param shadowBlur how much the blur the shadow (see `blur` parameter of
 *   [org.maplibre.compose.layers.CircleLayer])
 * @param accuracyStrokeWidth the stroke width of the accuracy circle's border
 * @param bearingSize the size of the bearing indicator
 */
@Immutable
public class LocationPuckSizes(
  public val dotRadius: Dp = 6.dp,
  public val dotStrokeWidth: Dp = 3.dp,
  public val shadowSize: Dp = 3.dp,
  public val shadowOffset: DpOffset = DpOffset(0.dp, 1.dp),
  public val shadowBlur: Float = 1f,
  public val accuracyStrokeWidth: Dp = 1.dp,
  public val bearingSize: Dp = dotRadius,
)
