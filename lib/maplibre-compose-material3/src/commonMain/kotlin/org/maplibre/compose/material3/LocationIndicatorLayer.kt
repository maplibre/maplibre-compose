package org.maplibre.compose.material3

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.LocationIndicatorLayer as BaseLocationIndicatorLayer
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.rememberDefaultHeadingProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.Rotation

/**
 * [org.maplibre.compose.layers.LocationIndicatorLayer] with colors and images from the Material 3
 * theme. Parameters and behavior match the core overload.
 */
@Composable
@MaplibreComposable
public fun LocationIndicatorLayer(
  id: String,
  locationState: LocationState =
    rememberLocationState(headingProvider = rememberDefaultHeadingProvider()),
  minZoom: Float = 0f,
  maxZoom: Float = 24f,
  visible: Boolean = true,
  bearingAccuracyRadius: Expression<DpValue> = const(48.dp),
  bearingAccuracyColor: Expression<ColorValue> =
    const(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
  accuracyRadiusColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
  accuracyRadiusBorderColor: Color = MaterialTheme.colorScheme.primary,
  topImage: Expression<ImageValue?>? = LocationIndicatorDefaults.topImage(),
  bearingImage: Expression<ImageValue?>? = LocationIndicatorDefaults.bearingImage(),
  shadowImage: Expression<ImageValue?>? = LocationIndicatorDefaults.shadowImage(),
  topImageSize: Float = 1f,
  bearingImageSize: Float = 1f,
  shadowImageSize: Float = 1f,
  imageTiltDisplacement: Float = 0f,
  perspectiveCompensation: Float = 0.85f,
  locationTransition: TransitionOptions = TransitionOptions(),
  bearingTransition: TransitionOptions = locationTransition,
  accuracyRadiusTransition: TransitionOptions = locationTransition,
  bearingAccuracyTransition: TransitionOptions = bearingTransition,
  onClick: (() -> ClickResult)? = null,
  onLongClick: (() -> ClickResult)? = null,
  onDoubleClick: (() -> ClickResult)? = null,
  hitPadding: Dp = 0.dp,
) {
  BaseLocationIndicatorLayer(
    id = id,
    locationState = locationState,
    minZoom = minZoom,
    maxZoom = maxZoom,
    visible = visible,
    bearingAccuracyRadius = bearingAccuracyRadius,
    bearingAccuracyColor = bearingAccuracyColor,
    accuracyRadiusColor = accuracyRadiusColor,
    accuracyRadiusBorderColor = accuracyRadiusBorderColor,
    topImage = topImage,
    bearingImage = bearingImage,
    shadowImage = shadowImage,
    topImageSize = topImageSize,
    bearingImageSize = bearingImageSize,
    shadowImageSize = shadowImageSize,
    imageTiltDisplacement = imageTiltDisplacement,
    perspectiveCompensation = perspectiveCompensation,
    locationTransition = locationTransition,
    bearingTransition = bearingTransition,
    accuracyRadiusTransition = accuracyRadiusTransition,
    bearingAccuracyTransition = bearingAccuracyTransition,
    onClick = onClick,
    onLongClick = onLongClick,
    onDoubleClick = onDoubleClick,
    hitPadding = hitPadding,
  )
}

/**
 * [org.maplibre.compose.layers.LocationIndicatorLayer] with colors and images from the Material 3
 * theme. Parameters and behavior match the core overload.
 */
@Composable
@MaplibreComposable
public fun LocationIndicatorLayer(
  id: String,
  location: Position?,
  bearing: Bearing? = null,
  accuracyRadius: Length? = null,
  bearingAccuracy: Rotation? = null,
  minZoom: Float = 0f,
  maxZoom: Float = 24f,
  visible: Boolean = true,
  bearingAccuracyRadius: Expression<DpValue> = const(48.dp),
  bearingAccuracyColor: Expression<ColorValue> =
    const(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
  accuracyRadiusColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
  accuracyRadiusBorderColor: Color = MaterialTheme.colorScheme.primary,
  topImage: Expression<ImageValue?>? = LocationIndicatorDefaults.topImage(),
  bearingImage: Expression<ImageValue?>? = LocationIndicatorDefaults.bearingImage(),
  shadowImage: Expression<ImageValue?>? = LocationIndicatorDefaults.shadowImage(),
  topImageSize: Float = 1f,
  bearingImageSize: Float = 1f,
  shadowImageSize: Float = 1f,
  imageTiltDisplacement: Float = 0f,
  perspectiveCompensation: Float = 0.85f,
  locationTransition: TransitionOptions = TransitionOptions(),
  bearingTransition: TransitionOptions = locationTransition,
  accuracyRadiusTransition: TransitionOptions = locationTransition,
  bearingAccuracyTransition: TransitionOptions = bearingTransition,
  onClick: (() -> ClickResult)? = null,
  onLongClick: (() -> ClickResult)? = null,
  onDoubleClick: (() -> ClickResult)? = null,
  hitPadding: Dp = 0.dp,
) {
  BaseLocationIndicatorLayer(
    id = id,
    location = location,
    bearing = bearing,
    accuracyRadius = accuracyRadius,
    bearingAccuracy = bearingAccuracy,
    minZoom = minZoom,
    maxZoom = maxZoom,
    visible = visible,
    bearingAccuracyRadius = bearingAccuracyRadius,
    bearingAccuracyColor = bearingAccuracyColor,
    accuracyRadiusColor = accuracyRadiusColor,
    accuracyRadiusBorderColor = accuracyRadiusBorderColor,
    topImage = topImage,
    bearingImage = bearingImage,
    shadowImage = shadowImage,
    topImageSize = topImageSize,
    bearingImageSize = bearingImageSize,
    shadowImageSize = shadowImageSize,
    imageTiltDisplacement = imageTiltDisplacement,
    perspectiveCompensation = perspectiveCompensation,
    locationTransition = locationTransition,
    bearingTransition = bearingTransition,
    accuracyRadiusTransition = accuracyRadiusTransition,
    bearingAccuracyTransition = bearingAccuracyTransition,
    onClick = onClick,
    onLongClick = onLongClick,
    onDoubleClick = onDoubleClick,
    hitPadding = hitPadding,
  )
}
