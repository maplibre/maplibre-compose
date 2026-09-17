package org.maplibre.compose.material3

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationPuckAnimation
import org.maplibre.compose.location.LocationPuckColors

/** Material 3 themed defaults for [LocationPuck] parameters */
public object LocationPuckDefaults {
  /** A fast spatial animation from the current Material motion scheme for the puck bearing. */
  @Composable
  public fun animation(): LocationPuckAnimation =
    LocationPuckAnimation(bearing = MaterialTheme.motionScheme.fastSpatialSpec())

  @Composable
  public fun colors(
    dotFillColorCurrentLocation: Color = MaterialTheme.colorScheme.primary,
    dotFillColorOldLocation: Color = MaterialTheme.colorScheme.surfaceDim,
    dotStrokeColor: Color = contentColorFor(dotFillColorCurrentLocation),
    accuracyStrokeColor: Color = dotFillColorCurrentLocation,
    bearingColor: Color = MaterialTheme.colorScheme.secondary,
  ): LocationPuckColors {
    return LocationPuckColors(
      dotFillColorCurrentLocation = dotFillColorCurrentLocation,
      dotFillColorOldLocation = dotFillColorOldLocation,
      dotStrokeColor = dotStrokeColor,
      accuracyStrokeColor = accuracyStrokeColor,
      bearingColor = bearingColor,
    )
  }
}
