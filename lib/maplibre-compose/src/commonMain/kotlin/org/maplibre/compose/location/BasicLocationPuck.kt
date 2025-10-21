package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.Path
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.FeatureCollection
import io.github.dellisd.spatialk.geojson.Point
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.div
import org.maplibre.compose.expressions.dsl.dp
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.gt
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.minus
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.plus
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult

/**
 * Adds multiple layers to form a location puck.
 *
 * A location puck is a dot at the users current location according to [locationState] and
 * optionally a circle for the location accuracy. If supported and enabled, indicators for the
 * current bearing and bearing accuracy are shown as well.
 *
 * @param idPrefix the prefix used for the layers to display the location indicator
 * @param locationState a [UserLocationState] holding the location to display
 * @param cameraState the [CameraState] of the map, used only for [CameraState.metersPerDpAtTarget]
 *   to correctly draw the accuracy circle. The camera state is not modified by this composable, if
 *   you want the camera to track the current location use [LocationTrackingEffect].
 * @param oldLocationThreshold locations with a [timestamp][Location.timestamp] older than this will
 *   be considered old locations
 * @param dotRadius the radius of the main location indicator dot
 * @param dotFillColorCurrentLocation the fill color of the main indicator dot, when location is
 *   *not* old according to [oldLocationThreshold]
 * @param dotFillColorOldLocation the fill color of the main indicator dot, when location *is*
 *   considered old according to [oldLocationThreshold]
 * @param dotStrokeColor the stroke color for the border of the main indicator dot
 * @param dotStrokeWidth the stroke width for the border of the main indicator dot
 * @param shadowSize if positive, a shadow will be drawn underneath the main indicator dot with a
 *   radius of `dotRadius + dotStrokeWidth + shadowSize`, i.e. the shadow extends [shadowSize]
 *   beyond the dot
 * @param shadowOffset an offset applied to the shadow of the main indicator dot
 * @param shadowColor the color of the main indicator's shadow
 * @param shadowBlur how much the blur the shadow (see `blur` parameter of [CircleLayer])
 * @param accuracyThreshold a circle showing the accuracy range will be drawn, when
 *   [Location.accuracy] is larger than this value. Use [Float.POSITIVE_INFINITY] to never show the
 *   accuracy range.
 * @param accuracyOpacity the opacity of the accuracy circle
 * @param accuracyFillColor the fill color of the accuracy circle
 * @param accuracyStrokeColor the stroke color of the accuracy circle's border
 * @param accuracyStrokeWidth the stroke width of the accuracy circle's border
 * @param showBearing whether to show an indicator for [Location.bearing]
 * @param bearingSize the size of the bearing indicator
 * @param bearingColor the color of the bearing indicator
 * @param showBearingAccuracy whether to show an indicator for [Location.bearingAccuracy]
 * @param onClick a [LocationClickHandler] to invoke when the main location indicator dot is clicked
 * @param onClick a [LocationClickHandler] to invoke when the main location indicator dot is
 *   long-clicked
 */
@Composable
public fun BasicLocationPuck(
  idPrefix: String,
  locationState: UserLocationState,
  cameraState: CameraState,
  oldLocationThreshold: Duration = 30.seconds,
  dotRadius: Dp = 7.dp,
  dotFillColorCurrentLocation: Color = Color.Blue,
  dotFillColorOldLocation: Color = Color.Gray,
  dotStrokeColor: Color = Color.White,
  dotStrokeWidth: Dp = 3.dp,
  shadowSize: Dp = 3.dp,
  shadowOffset: DpOffset = DpOffset(0.dp, 1.dp),
  shadowColor: Color = Color.Black,
  shadowBlur: Float = 1f,
  accuracyThreshold: Float = 50f,
  accuracyOpacity: Float = 0.3f,
  accuracyFillColor: Color = Color.Blue,
  accuracyStrokeColor: Color = accuracyFillColor,
  accuracyStrokeWidth: Dp = 1.dp,
  showBearing: Boolean = true,
  bearingSize: Dp = 6.dp,
  bearingColor: Color = Color.Red,
  showBearingAccuracy: Boolean = true,
  onClick: LocationClickHandler? = null,
  onLongClick: LocationClickHandler? = null,
) {
  val bearingPainter =
    rememberVectorPainter(
      defaultWidth = bearingSize,
      defaultHeight = bearingSize,
      autoMirror = false,
    ) { viewportWidth, viewportHeight ->
      Path(
        pathData =
          PathData {
            moveTo(0f, 0f)
            lineTo(0f, viewportHeight)
            lineTo(viewportWidth, 0f)
            close()
          },
        fill = SolidColor(bearingColor),
      )
    }

  val density by rememberUpdatedState(LocalDensity.current)

  val dotRadius by rememberUpdatedState(dotRadius)
  val dotStrokeWidth by rememberUpdatedState(dotStrokeWidth)
  val bearingColor by rememberUpdatedState(bearingColor)

  val bearingAccuracyVector by remember {
    derivedStateOf {
      val radius = with(density) { Offset(dotRadius.toPx(), dotRadius.toPx()) }

      val deltaDegrees = 2 * (locationState.location?.bearingAccuracy ?: 0.0)
      val delta = (PI * deltaDegrees / 180.0).toFloat()

      val start = Offset(radius.x, 0f) + radius * 2f
      val end = Offset(radius.x * cos(delta), radius.y * sin(delta)) + radius * 2f

      ImageVector.Builder(
          defaultWidth = 4 * dotRadius,
          defaultHeight = 4 * dotRadius,
          viewportWidth = with(density) { (4 * dotRadius.toPx()) },
          viewportHeight = with(density) { (4 * dotRadius.toPx()) },
          autoMirror = false,
        )
        .apply {
          path(
            stroke = SolidColor(bearingColor),
            strokeLineWidth = with(density) { dotStrokeWidth.toPx() },
          ) {
            moveTo(start.x, start.y)
            arcTo(radius.x, radius.y, 0f, delta > PI, delta > 0, end.x, end.y)
          }
        }
        .build()
    }
  }

  val bearingAccuracyPainter = rememberVectorPainter(bearingAccuracyVector)

  val features =
    remember(locationState.location) {
      val location = locationState.location
      if (location == null) {
        FeatureCollection()
      } else {
        FeatureCollection(
          Feature(
            geometry = Point(location.position),
            properties =
              mapOf(
                "accuracy" to JsonPrimitive(location.accuracy),
                "bearing" to JsonPrimitive(location.bearing),
                "bearingAccuracy" to JsonPrimitive(location.bearingAccuracy),
                "age" to JsonPrimitive(location.timestamp.elapsedNow().inWholeNanoseconds),
              ),
          )
        )
      }
    }

  val locationSource = rememberGeoJsonSource(GeoJsonData.Features(features))

  CircleLayer(
    id = "$idPrefix-accuracy",
    source = locationSource,
    visible =
      accuracyThreshold <= Float.POSITIVE_INFINITY &&
        locationState.location.let { it != null && it.accuracy > accuracyThreshold },
    radius =
      switch(
        condition(
          test =
            feature["age"].asNumber() gt const(oldLocationThreshold.inWholeNanoseconds.toFloat()),
          output = const(0.dp),
        ),
        fallback =
          (feature["accuracy"].asNumber() / const(cameraState.metersPerDpAtTarget.toFloat())).dp,
      ),
    color = const(accuracyFillColor),
    strokeColor = const(accuracyStrokeColor),
    strokeWidth = const(accuracyStrokeWidth),
    opacity = const(accuracyOpacity),
  )

  CircleLayer(
    id = "$idPrefix-shadow",
    source = locationSource,
    visible = shadowSize > 0.dp && locationState.location != null,
    radius = const(dotRadius + dotStrokeWidth + shadowSize),
    color = const(shadowColor),
    blur = const(shadowBlur),
    translate = const(DpOffset(0.dp, 1.dp)),
  )

  CircleLayer(
    id = "$idPrefix-dot",
    source = locationSource,
    visible = locationState.location != null,
    radius = const(dotRadius),
    color =
      switch(
        condition(
          test =
            feature["age"].asNumber() gt const(oldLocationThreshold.inWholeNanoseconds.toFloat()),
          output = const(dotFillColorOldLocation),
        ),
        fallback = const(dotFillColorCurrentLocation),
      ),
    strokeColor = const(dotStrokeColor),
    strokeWidth = const(dotStrokeWidth),
    onClick = {
      locationState.location?.let { onClick?.invoke(it) }
      ClickResult.Consume
    },
    onLongClick = {
      locationState.location?.let { onLongClick?.invoke(it) }
      ClickResult.Consume
    },
  )

  SymbolLayer(
    id = "$idPrefix-bearing",
    source = locationSource,
    visible = showBearing && locationState.location?.bearing != null,
    iconImage = image(bearingPainter),
    iconAnchor = const(SymbolAnchor.Center),
    iconRotate = feature["bearing"].asNumber(const(0f)) + const(45f),
    iconOffset =
      offset(
        -(dotRadius + dotStrokeWidth) * sqrt(2f) / 2f,
        -(dotRadius + dotStrokeWidth) * sqrt(2f) / 2f,
      ),
    iconRotationAlignment = const(IconRotationAlignment.Map),
    iconAllowOverlap = const(true),
  )

  SymbolLayer(
    id = "$idPrefix-bearingAccuracy",
    source = locationSource,
    visible =
      showBearingAccuracy &&
        locationState.location?.bearing != null &&
        locationState.location?.bearingAccuracy != null,
    iconImage = image(bearingAccuracyPainter),
    iconAnchor = const(SymbolAnchor.Center),
    iconRotate =
      feature["bearing"].asNumber(const(0f)) -
        const(90f) -
        feature["bearingAccuracy"].asNumber(const(0f)),
    iconRotationAlignment = const(IconRotationAlignment.Map),
    iconAllowOverlap = const(true),
  )
}

public typealias LocationClickHandler = (Location) -> Unit
