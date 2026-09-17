package org.maplibre.compose.location

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable

/**
 * Motion of the values rendered by [LocationPuck]. Location measurements remain unchanged.
 *
 * @param bearing Animation of the bearing, in degrees. The first bearing appears immediately. Later
 *   bearings target the nearest equivalent angle across north; the default spring preserves
 *   velocity when interrupted. An absent bearing hides the indicator and resets the animation. Set
 *   this spec to `null` to render caller-controlled bearings directly.
 */
@Immutable
public data class LocationPuckAnimation(
  public val bearing: FiniteAnimationSpec<Float>? =
    spring(
      dampingRatio = Spring.DampingRatioNoBouncy,
      stiffness = Spring.StiffnessMedium,
      visibilityThreshold = 0.1f,
    )
)
