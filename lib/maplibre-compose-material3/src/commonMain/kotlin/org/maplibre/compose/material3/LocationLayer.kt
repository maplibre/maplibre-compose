package org.maplibre.compose.material3

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
import org.maplibre.compose.layers.BasicLocationLayer
import org.maplibre.compose.layers.LocationClickHandler
import org.maplibre.compose.location.UserLocationState

/**
 * A Material 3 themed variant of [BasicLocationLayer].
 *
 * @see BasicLocationLayer
 */
@Composable
public fun LocationLayer(
  id: String,
  locationState: UserLocationState,
  cameraState: CameraState,
  oldLocationThreshold: Duration = 30.seconds,
  dotRadius: Dp = 7.dp,
  dotFillColorCurrentLocation: Color = MaterialTheme.colorScheme.primary,
  dotFillColorOldLocation: Color = MaterialTheme.colorScheme.surfaceDim,
  dotStrokeColor: Color = contentColorFor(MaterialTheme.colorScheme.primary),
  dotStrokeWidth: Dp = 3.dp,
  shadowSize: Dp = 3.dp,
  shadowOffset: DpOffset = DpOffset(0.dp, 1.dp),
  shadowColor: Color = Color.Black,
  shadowBlur: Float = 1f,
  accuracyThreshold: Float = 50f,
  accuracyOpacity: Float = 0.3f,
  accuracyFillColor: Color = MaterialTheme.colorScheme.primary,
  accuracyStrokeColor: Color = accuracyFillColor,
  accuracyStrokeWidth: Dp = 1.dp,
  showBearing: Boolean = true,
  bearingSize: Dp = 5.dp,
  bearingColor: Color = MaterialTheme.colorScheme.secondary,
  showBearingAccuracy: Boolean = true,
  onClick: LocationClickHandler? = null,
  onLongClick: LocationClickHandler? = null,
) {
  BasicLocationLayer(
    id = id,
    locationState = locationState,
    cameraState = cameraState,
    oldLocationThreshold = oldLocationThreshold,
    dotRadius = dotRadius,
    dotFillColorCurrentLocation = dotFillColorCurrentLocation,
    dotFillColorOldLocation = dotFillColorOldLocation,
    dotStrokeColor = dotStrokeColor,
    dotStrokeWidth = dotStrokeWidth,
    shadowSize = shadowSize,
    shadowOffset = shadowOffset,
    shadowColor = shadowColor,
    shadowBlur = shadowBlur,
    accuracyThreshold = accuracyThreshold,
    accuracyOpacity = accuracyOpacity,
    accuracyFillColor = accuracyFillColor,
    accuracyStrokeColor = accuracyStrokeColor,
    accuracyStrokeWidth = accuracyStrokeWidth,
    showBearing = showBearing,
    bearingSize = bearingSize,
    bearingColor = bearingColor,
    showBearingAccuracy = showBearingAccuracy,
    onClick = onClick,
    onLongClick = onLongClick,
  )
}
