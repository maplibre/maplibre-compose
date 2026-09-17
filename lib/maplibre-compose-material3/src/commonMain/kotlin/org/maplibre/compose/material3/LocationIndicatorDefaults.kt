package org.maplibre.compose.material3

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.layers.LocationIndicatorDefaults as CoreDefaults

/** Material-themed images for the location indicator. */
public object LocationIndicatorDefaults {
  /** The location dot in the theme's primary color. */
  @Composable
  public fun topImage(): Expression<ImageValue?> =
    CoreDefaults.topImage(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)

  /** The bearing arrow in the theme's primary color. */
  @Composable
  public fun bearingImage(): Expression<ImageValue?> =
    CoreDefaults.bearingImage(MaterialTheme.colorScheme.primary)
}
