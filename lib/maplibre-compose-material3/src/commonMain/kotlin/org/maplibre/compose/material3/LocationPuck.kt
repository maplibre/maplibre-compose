package org.maplibre.compose.material3

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.location.BasicLocationPuck
import org.maplibre.compose.location.LocationClickHandler
import org.maplibre.compose.location.LocationPuckColors
import org.maplibre.compose.location.UserLocationState

/**
 * A Material 3 themed variant of [BasicLocationPuck].
 *
 * @see BasicLocationPuck
 */
@Composable
public fun LocationPuck(
  id: String,
  locationState: UserLocationState,
  cameraState: CameraState,
  oldLocationThreshold: Duration = 30.seconds,
  dotRadius: Dp = 7.dp,
  dotStrokeWidth: Dp = 3.dp,
  shadowSize: Dp = 3.dp,
  shadowOffset: DpOffset = DpOffset(0.dp, 1.dp),
  shadowColor: Color = Color.Black,
  shadowBlur: Float = 1f,
  accuracyThreshold: Float = 50f,
  accuracyOpacity: Float = 0.3f,
  accuracyStrokeWidth: Dp = 1.dp,
  showBearing: Boolean = true,
  bearingSize: Dp = 5.dp,
  showBearingAccuracy: Boolean = true,
  onClick: LocationClickHandler? = null,
  onLongClick: LocationClickHandler? = null,
) {
  BasicLocationPuck(
    idPrefix = id,
    locationState = locationState,
    cameraState = cameraState,
    oldLocationThreshold = oldLocationThreshold,
    accuracyThreshold = accuracyThreshold,
    showBearing = showBearing,
    showBearingAccuracy = showBearingAccuracy,
    colors = MaterialTheme.colorScheme.locationPuckColors(),
    onClick = onClick,
    onLongClick = onLongClick,
  )
}

public fun ColorScheme.locationPuckColors(): LocationPuckColors {
  return LocationPuckColors(
    dotFillColorCurrentLocation = this.primary,
    dotFillColorOldLocation = this.surfaceDim,
    dotStrokeColor = contentColorFor(this.primary),
    accuracyStrokeColor = this.primary,
    bearingColor = this.secondary,
  )
}
