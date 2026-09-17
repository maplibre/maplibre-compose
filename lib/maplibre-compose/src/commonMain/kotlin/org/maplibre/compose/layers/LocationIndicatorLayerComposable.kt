package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.interaction.ClickResult
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
 * or an accuracy threshold. Use the value overload for other display policies. Other parameters
 * behave as in the [Position] overload.
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
  shadowImage: Expression<ImageValue?>? = LocationIndicatorDefaults.shadowImage(),
  topImageSize: Float = 1f,
  bearingImageSize: Float = 1f,
  shadowImageSize: Float = 1f,
  imageTiltDisplacement: Float = 0f,
  perspectiveCompensation: Float = 0.85f,
  locationTransition: TransitionOptions = TransitionOptions(),
  bearingTransition: TransitionOptions = locationTransition,
  accuracyRadiusTransition: TransitionOptions = locationTransition,
  onClick: (() -> ClickResult)? = null,
  onLongClick: (() -> ClickResult)? = null,
  onDoubleClick: (() -> ClickResult)? = null,
  hitPadding: Dp = 0.dp,
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
    onClick = onClick,
    onLongClick = onLongClick,
    onDoubleClick = onDoubleClick,
    hitPadding = hitPadding,
  )
}

/**
 * Draws a location dot, an optional bearing image, and a horizontal accuracy circle.
 *
 * The first location appears immediately; later measurements animate with the supplied transition
 * options. Bearing and longitude changes take the shortest path across north and the antimeridian.
 * A null [location] removes the indicator and resets its animation history.
 *
 * Click handlers target the top and bearing image bounds, including transparent margins, but not
 * the shadow or accuracy circle. Each gesture invokes its handler at most once, even when the
 * images overlap. Return [ClickResult.Pass] to continue to layers below or [ClickResult.Consume] to
 * stop dispatch.
 *
 * @param id Unique layer ID.
 * @param location Position of the indicator, or null to hide it. Altitude is not rendered.
 * @param bearing Rotation of all three images clockwise from north. Null hides [bearingImage] and
 *   resets the other images to zero rotation. Its first available value appears immediately.
 * @param accuracyRadius Horizontal error radius in meters, or null to hide the circle.
 * @param minZoom Minimum visible zoom, inclusive.
 * @param maxZoom Maximum visible zoom, exclusive.
 * @param visible Whether to draw the indicator.
 * @param accuracyRadiusColor Fill color of the horizontal accuracy circle.
 * @param accuracyRadiusBorderColor Border color of the horizontal accuracy circle.
 * @param topImage Top image, normally the dot. Null omits it. Images must resolve to constant
 *   names.
 * @param bearingImage Image below [topImage]. Null omits it.
 * @param shadowImage Image below the other images. Null omits it.
 * @param topImageSize Scale of [topImage].
 * @param bearingImageSize Scale of [bearingImage].
 * @param shadowImageSize Scale of [shadowImage].
 * @param imageTiltDisplacement Image displacement when pitched, in pixels.
 * @param perspectiveCompensation Perspective compensation: zero follows map perspective; one
 *   preserves screen size.
 * @param locationTransition Duration and delay for position changes.
 * @param bearingTransition Bearing transition timing, defaulting to [locationTransition].
 * @param accuracyRadiusTransition Accuracy transition timing, defaulting to [locationTransition].
 * @param onClick Called when an indicator image is clicked.
 * @param onLongClick Called for a touch long press or secondary mouse click.
 * @param onDoubleClick Called for a double tap or double click.
 * @param hitPadding Expands tap queries to a square of this radius in dp; zero uses a point.
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
  shadowImage: Expression<ImageValue?>? = LocationIndicatorDefaults.shadowImage(),
  topImageSize: Float = 1f,
  bearingImageSize: Float = 1f,
  shadowImageSize: Float = 1f,
  imageTiltDisplacement: Float = 0f,
  perspectiveCompensation: Float = 0.85f,
  locationTransition: TransitionOptions = TransitionOptions(),
  bearingTransition: TransitionOptions = locationTransition,
  accuracyRadiusTransition: TransitionOptions = locationTransition,
  onClick: (() -> ClickResult)? = null,
  onLongClick: (() -> ClickResult)? = null,
  onDoubleClick: (() -> ClickResult)? = null,
  hitPadding: Dp = 0.dp,
) {
  require(
    accuracyRadius == null || (accuracyRadius.inMeters.isFinite() && accuracyRadius.inMeters >= 0)
  ) {
    "accuracyRadius must be finite and nonnegative"
  }
  require(hitPadding.value.isFinite() && hitPadding.value >= 0f) {
    "hitPadding must be finite and nonnegative"
  }
  if (location == null) return
  key(id) {
    ComposeLocationIndicator(
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
        onClick = onClick,
        onLongClick = onLongClick,
        onDoubleClick = onDoubleClick,
        hitPadding = hitPadding,
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
  val onClick: (() -> ClickResult)?,
  val onLongClick: (() -> ClickResult)?,
  val onDoubleClick: (() -> ClickResult)?,
  val hitPadding: Dp,
)

internal fun (() -> ClickResult).asFeaturesClickHandler(): FeaturesClickHandler = { this() }
