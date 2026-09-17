package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.mostAccurateBearing
import org.maplibre.compose.location.rememberDefaultHeadingProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.extensions.inMeters

/**
 * Displays the latest location and the most accurate course or device heading from [locationState].
 *
 * By default, remembers lifecycle-aware location and heading providers. Permission is never
 * requested automatically. Pass a hoisted [rememberLocationState] to request permission, inspect
 * provider status, share measurements with camera tracking, or supply a custom provider.
 *
 * Retained measurements remain visible when tracking stops. The layer does not apply stale styling
 * or an accuracy threshold. Use the value overload for other display policies. Rendering parameters
 * and platform limitations are the same as the [Position] overload.
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
  accuracyRadiusColor: Color = Color.Blue.copy(alpha = 0.15f),
  accuracyRadiusBorderColor: Color = Color.Blue,
  topImage: Expression<ImageValue?>? = LocationIndicatorDefaults.topImage(),
  bearingImage: Expression<ImageValue?>? = LocationIndicatorDefaults.bearingImage(),
  shadowImage: Expression<ImageValue?>? = null,
  topImageSize: Float = 1f,
  bearingImageSize: Float = 1f,
  shadowImageSize: Float = 1f,
  imageTiltDisplacement: Float = 0f,
  perspectiveCompensation: Float = 0.85f,
  locationTransition: TransitionOptions = TransitionOptions(),
  bearingTransition: TransitionOptions = locationTransition,
  accuracyRadiusTransition: TransitionOptions = locationTransition,
) {
  LocationIndicatorLayer(
    id = id,
    location = locationState.lastLocation?.position,
    bearing = locationState.mostAccurateBearing(),
    accuracyRadius = locationState.lastLocation?.horizontalAccuracy,
    minZoom = minZoom,
    maxZoom = maxZoom,
    visible = visible,
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
  )
}

/**
 * Draws a location dot, an optional bearing image, and a horizontal accuracy circle.
 *
 * Native platforms use MapLibre's source-free location-indicator renderer. Its paint transitions
 * animate position, bearing, and accuracy inside the engine. Bearings cross north by the short
 * path; longitude targets are unwrapped across the antimeridian. The first location appears
 * immediately. A null [location] removes the indicator and resets its history.
 *
 * JS uses a GeoJSON point, circle, and symbol layers. Measurement updates apply immediately:
 * [locationTransition], [bearingTransition], and [accuracyRadiusTransition] are unsupported on JS.
 * [imageTiltDisplacement] and [perspectiveCompensation] are also unsupported on JS; its images
 * align with the map plane. The JS circle approximates meters using Mercator scale at the location
 * latitude; globe rendering is not supported.
 *
 * There is no bearing-accuracy sector or click handling. This component does not request
 * permissions, select a provider, follow the camera, or change measurement state.
 *
 * @param id Unique indicator name. JS reserves the derived layer IDs `id-accuracy`, `id-shadow`,
 *   `id-bearing`, and `id-top`.
 * @param location Position of the indicator, or null to hide it. Altitude is not rendered.
 * @param bearing Direction clockwise from north, or null to hide the bearing image. Its first
 *   available value appears immediately.
 * @param accuracyRadius Horizontal error radius in meters, or null to hide the circle.
 * @param minZoom Minimum visible zoom, inclusive.
 * @param maxZoom Maximum visible zoom, exclusive.
 * @param visible Whether to draw the indicator.
 * @param accuracyRadiusColor Fill color of the horizontal accuracy circle.
 * @param accuracyRadiusBorderColor Border color of the horizontal accuracy circle.
 * @param topImage Top image, normally the dot. Null omits it. Images must resolve to constant
 *   names.
 * @param bearingImage Image below [topImage], rotated by [bearing]. Null omits it.
 * @param shadowImage Image below the other images. Null omits it.
 * @param topImageSize Scale of [topImage].
 * @param bearingImageSize Scale of [bearingImage].
 * @param shadowImageSize Scale of [shadowImage].
 * @param imageTiltDisplacement Native image displacement when pitched, in pixels.
 * @param perspectiveCompensation Native perspective compensation: zero follows map perspective; one
 *   preserves screen size.
 * @param locationTransition Native position transition timing. Zero duration applies immediately.
 * @param bearingTransition Native bearing transition timing, defaulting to [locationTransition].
 * @param accuracyRadiusTransition Native accuracy transition timing, defaulting to
 *   [locationTransition].
 */
@Composable
@MaplibreComposable
public fun LocationIndicatorLayer(
  id: String,
  location: Position?,
  bearing: Bearing? = null,
  accuracyRadius: Length? = null,
  minZoom: Float = 0f,
  maxZoom: Float = 24f,
  visible: Boolean = true,
  accuracyRadiusColor: Color = Color.Blue.copy(alpha = 0.15f),
  accuracyRadiusBorderColor: Color = Color.Blue,
  topImage: Expression<ImageValue?>? = LocationIndicatorDefaults.topImage(),
  bearingImage: Expression<ImageValue?>? = LocationIndicatorDefaults.bearingImage(),
  shadowImage: Expression<ImageValue?>? = null,
  topImageSize: Float = 1f,
  bearingImageSize: Float = 1f,
  shadowImageSize: Float = 1f,
  imageTiltDisplacement: Float = 0f,
  perspectiveCompensation: Float = 0.85f,
  locationTransition: TransitionOptions = TransitionOptions(),
  bearingTransition: TransitionOptions = locationTransition,
  accuracyRadiusTransition: TransitionOptions = locationTransition,
) {
  require(
    accuracyRadius == null || (accuracyRadius.inMeters.isFinite() && accuracyRadius.inMeters >= 0)
  ) {
    "accuracyRadius must be finite and nonnegative"
  }
  if (location == null) return
  key(id) {
    PlatformLocationIndicator(
      LocationIndicatorProperties(
        id = id,
        location = location,
        bearing = bearing,
        accuracyRadius = accuracyRadius,
        minZoom = minZoom,
        maxZoom = maxZoom,
        visible = visible,
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
      )
    )
  }
}

internal data class LocationIndicatorProperties(
  val id: String,
  val location: Position,
  val bearing: Bearing?,
  val accuracyRadius: Length?,
  val minZoom: Float,
  val maxZoom: Float,
  val visible: Boolean,
  val accuracyRadiusColor: Color,
  val accuracyRadiusBorderColor: Color,
  val topImage: Expression<ImageValue?>?,
  val bearingImage: Expression<ImageValue?>?,
  val shadowImage: Expression<ImageValue?>?,
  val topImageSize: Float,
  val bearingImageSize: Float,
  val shadowImageSize: Float,
  val imageTiltDisplacement: Float,
  val perspectiveCompensation: Float,
  val locationTransition: TransitionOptions,
  val bearingTransition: TransitionOptions,
  val accuracyRadiusTransition: TransitionOptions,
)

@Composable
@MaplibreComposable
internal expect fun PlatformLocationIndicator(properties: LocationIndicatorProperties)
