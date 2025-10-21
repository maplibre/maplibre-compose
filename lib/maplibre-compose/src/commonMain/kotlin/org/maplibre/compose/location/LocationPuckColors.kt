package org.maplibre.compose.location

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colors for [LocationPuck]
 *
 * @param dotFillColorCurrentLocation the fill color of the main indicator dot, when location is
 *   *not* old
 * @param dotFillColorOldLocation the fill color of the main indicator dot, when location *is*
 *   considered old
 * @param dotStrokeColor the stroke color for the border of the main indicator dot
 * @param shadowColor the color of the main indicator's shadow
 * @param accuracyFillColor the fill color of the accuracy circle
 * @param accuracyStrokeColor the stroke color of the accuracy circle's border
 * @param bearingColor the color of the bearing indicator
 */
@Immutable
public class LocationPuckColors(
  public val dotFillColorCurrentLocation: Color = Color.Companion.Blue,
  public val dotFillColorOldLocation: Color = Color.Companion.Gray,
  public val dotStrokeColor: Color = Color.Companion.White,
  public val shadowColor: Color = Color.Companion.Black,
  public val accuracyStrokeColor: Color = Color.Companion.Blue,
  public val accuracyFillColor: Color = accuracyStrokeColor.copy(alpha = 0.3f),
  public val bearingColor: Color = Color.Companion.Red,
)
