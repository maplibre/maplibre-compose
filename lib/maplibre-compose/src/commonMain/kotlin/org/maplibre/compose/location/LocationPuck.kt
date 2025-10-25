package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.Path
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
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
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point

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
 * @param accuracyThreshold a circle showing the accuracy range will be drawn, when
 *   [Location.accuracy] is larger than this value. Use [Float.POSITIVE_INFINITY] to never show the
 *   accuracy range.
 * @param showBearing whether to show an indicator for [Location.bearing]
 * @param showBearingAccuracy whether to show an indicator for [Location.bearingAccuracy]
 * @param onClick a [LocationClickHandler] to invoke when the main location indicator dot is clicked
 * @param onClick a [LocationClickHandler] to invoke when the main location indicator dot is
 *   long-clicked
 */
@Composable
public fun LocationPuck(
  idPrefix: String,
  locationState: UserLocationState,
  cameraState: CameraState,
  oldLocationThreshold: Duration = 30.seconds,
  accuracyThreshold: Float = 50f,
  colors: LocationPuckColors = LocationPuckColors(),
  sizes: LocationPuckSizes = LocationPuckSizes(),
  showBearing: Boolean = true,
  showBearingAccuracy: Boolean = true,
  onClick: LocationClickHandler? = null,
  onLongClick: LocationClickHandler? = null,
) {
  val bearingPainter = rememberBearingPainter(sizes, colors)
  val bearingAccuracyPainter =
    rememberBearingAccuracyPainter(
      sizes = sizes,
      colors = colors,
      bearingAccuracy = locationState.location?.bearingAccuracy ?: 0.0,
    )
  val locationSource = rememberLocationSource(locationState)

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
    color = const(colors.accuracyFillColor),
    strokeColor = const(colors.accuracyStrokeColor),
    strokeWidth = const(sizes.accuracyStrokeWidth),
  )

  CircleLayer(
    id = "$idPrefix-shadow",
    source = locationSource,
    visible = sizes.shadowSize > 0.dp && locationState.location != null,
    radius = const(sizes.dotRadius + sizes.dotStrokeWidth + sizes.shadowSize),
    color = const(colors.shadowColor),
    blur = const(sizes.shadowBlur),
    translate = const(DpOffset(0.dp, 1.dp)),
  )

  CircleLayer(
    id = "$idPrefix-dot",
    source = locationSource,
    visible = locationState.location != null,
    radius = const(sizes.dotRadius),
    color =
      switch(
        condition(
          test =
            feature["age"].asNumber() gt const(oldLocationThreshold.inWholeNanoseconds.toFloat()),
          output = const(colors.dotFillColorOldLocation),
        ),
        fallback = const(colors.dotFillColorCurrentLocation),
      ),
    strokeColor = const(colors.dotStrokeColor),
    strokeWidth = const(sizes.dotStrokeWidth),
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
        -(sizes.dotRadius + sizes.dotStrokeWidth) * sqrt(2f) / 2f,
        -(sizes.dotRadius + sizes.dotStrokeWidth) * sqrt(2f) / 2f,
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

@Composable
private fun rememberBearingPainter(
  sizes: LocationPuckSizes,
  colors: LocationPuckColors,
): VectorPainter {
  return rememberVectorPainter(
    defaultWidth = sizes.bearingSize,
    defaultHeight = sizes.bearingSize,
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
      fill = SolidColor(colors.bearingColor),
    )
  }
}

@Composable
private fun rememberBearingAccuracyPainter(
  sizes: LocationPuckSizes,
  colors: LocationPuckColors,
  bearingAccuracy: Double,
): VectorPainter {
  val density by rememberUpdatedState(LocalDensity.current)

  val dotRadius by rememberUpdatedState(sizes.dotRadius)
  val dotStrokeWidth by rememberUpdatedState(sizes.dotStrokeWidth)
  val bearingColor by rememberUpdatedState(colors.bearingColor)

  val bearingAccuracy by rememberUpdatedState(bearingAccuracy)

  val bearingAccuracyVector by remember {
    derivedStateOf {
      val radius = with(density) { Offset(dotRadius.toPx(), dotRadius.toPx()) }

      val deltaDegrees = 2 * bearingAccuracy
      val delta = (PI * deltaDegrees / 180.0).toFloat()

      val width = 2 * dotRadius + 2 * dotStrokeWidth
      val height = 2 * dotRadius + 2 * dotStrokeWidth

      val center = with(density) { Offset((width / 2).toPx(), (height / 2).toPx()) }

      val start = center + Offset(radius.x, 0f)
      val end = center + Offset(radius.x * cos(delta), radius.y * sin(delta))

      ImageVector.Builder(
          defaultWidth = width,
          defaultHeight = height,
          viewportWidth = with(density) { width.toPx() },
          viewportHeight = with(density) { height.toPx() },
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

  return rememberVectorPainter(bearingAccuracyVector)
}

@Composable
private fun rememberLocationSource(locationState: UserLocationState): GeoJsonSource {
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

  return rememberGeoJsonSource(GeoJsonData.Features(features))
}

public typealias LocationClickHandler = (Location) -> Unit
