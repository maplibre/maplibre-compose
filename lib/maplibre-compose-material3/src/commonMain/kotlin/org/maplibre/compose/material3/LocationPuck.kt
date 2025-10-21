package org.maplibre.compose.material3

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.contentColorFor
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationPuckColors

/**
 * A Material 3 themed variant of [LocationPuckColors].
 *
 * @see LocationPuck
 */
public fun ColorScheme.locationPuckColors(): LocationPuckColors {
  return LocationPuckColors(
    dotFillColorCurrentLocation = this.primary,
    dotFillColorOldLocation = this.surfaceDim,
    dotStrokeColor = contentColorFor(this.primary),
    accuracyStrokeColor = this.primary,
    bearingColor = this.secondary,
  )
}
