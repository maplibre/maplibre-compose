package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import kotlin.time.TimeMark
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.div
import org.maplibre.compose.expressions.dsl.dp
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.minus
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.plus
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.CirclePitchAlignment
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.LocalViewport
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.Rotation
import org.maplibre.spatialk.units.extensions.inDegrees
import org.maplibre.spatialk.units.extensions.inMeters
import org.maplibre.spatialk.units.extensions.meters

/**
 * Adds multiple layers to form a location puck from lifecycle-aware [locationState].
 *
 * The puck displays [LocationState.lastLocation], schedules its stale styling from the monotonic
 * measurement age, and selects the more accurate of the travel course and device heading for its
 * bearing indicator. Use the [LocationMeasurement] overload to display an arbitrary or replayed
 * measurement instead.
 *
 * @param idPrefix The prefix used for the layers to display the location indicator.
 * @param locationState State providing the location and heading measurements to display.
 * @param oldLocationThreshold Locations older than this will be styled differently.
 * @param accuracyThreshold A circle showing the accuracy range will be drawn when
 *   [LocationMeasurement.horizontalAccuracy] is larger than this value. Use
 *   [Length.PositiveInfinity] to hide the accuracy range.
 * @param colors The colors to use for the location puck.
 * @param sizes The sizes to use for the location puck.
 * @param onClick A [LocationClickHandler] to invoke when the main location indicator dot is
 *   clicked.
 * @param onLongClick A [LocationClickHandler] to invoke when the main location indicator dot is
 *   long-clicked.
 */
@Composable
public fun LocationPuck(
  idPrefix: String,
  locationState: LocationState,
  oldLocationThreshold: Duration = 30.seconds,
  accuracyThreshold: Length = 50.meters,
  colors: LocationPuckColors = LocationPuckColors(),
  sizes: LocationPuckSizes = LocationPuckSizes(),
  onClick: LocationClickHandler? = null,
  onLongClick: LocationClickHandler? = null,
) {
  LocationPuckContent(
    idPrefix = idPrefix,
    measurement =
      locationPuckMeasurement(
        location = locationState.lastLocation,
        measurementMark = locationState.lastLocationMeasurementMark,
        bearing = locationState.mostAccurateBearing(),
        bearingAccuracy = locationState.mostAccurateBearingAccuracy(),
      ),
    oldLocationThreshold = oldLocationThreshold,
    accuracyThreshold = accuracyThreshold,
    colors = colors,
    sizes = sizes,
    onClick = onClick,
    onLongClick = onLongClick,
  )
}

/**
 * Adds multiple layers to form a location puck.
 *
 * A location puck is a dot at the user's current location according to [location] and optionally a
 * circle for the location accuracy. If supported and enabled, indicators for the current bearing
 * and bearing accuracy are shown as well.
 *
 * @param idPrefix The prefix used for the layers to display the location indicator.
 * @param location The [LocationMeasurement] providing the current or last known location.
 * @param measurementMark Process-local monotonic mark that determines the age of [location]. A
 *   `null` value keeps the location styled as current.
 * @param bearing The bearing that rotates the location puck indicator. The default value is
 *   `location.course`, the direction of travel.
 * @param bearingAccuracy Estimated bearing error. Defaults to `location.courseAccuracy` when
 *   [bearing] is the location course.
 * @param oldLocationThreshold Locations older than this will be styled differently.
 * @param accuracyThreshold A circle showing the accuracy range will be drawn when
 *   [LocationMeasurement.horizontalAccuracy] is larger than this value. Use
 *   [Length.PositiveInfinity] to hide the accuracy range.
 * @param colors The colors to use for the location puck.
 * @param sizes The sizes to use for the location puck.
 * @param onClick A [LocationClickHandler] to invoke when the main location indicator dot is
 *   clicked.
 * @param onLongClick A [LocationClickHandler] to invoke when the main location indicator dot is
 *   long-clicked.
 */
@Composable
public fun LocationPuck(
  idPrefix: String,
  location: LocationMeasurement?,
  measurementMark: TimeMark? = null,
  bearing: Bearing? = location?.course,
  bearingAccuracy: Rotation? = defaultBearingAccuracy(location, bearing),
  oldLocationThreshold: Duration = 30.seconds,
  accuracyThreshold: Length = 50.meters,
  colors: LocationPuckColors = LocationPuckColors(),
  sizes: LocationPuckSizes = LocationPuckSizes(),
  onClick: LocationClickHandler? = null,
  onLongClick: LocationClickHandler? = null,
) {
  LocationPuckContent(
    idPrefix = idPrefix,
    measurement = locationPuckMeasurement(location, measurementMark, bearing, bearingAccuracy),
    oldLocationThreshold = oldLocationThreshold,
    accuracyThreshold = accuracyThreshold,
    colors = colors,
    sizes = sizes,
    onClick = onClick,
    onLongClick = onLongClick,
  )
}

@Composable
private fun LocationPuckContent(
  idPrefix: String,
  measurement: LocationPuckMeasurement?,
  oldLocationThreshold: Duration,
  accuracyThreshold: Length,
  colors: LocationPuckColors,
  sizes: LocationPuckSizes,
  onClick: LocationClickHandler?,
  onLongClick: LocationClickHandler?,
) {
  val viewport = LocalViewport.current
  val location = measurement?.location
  val bearing = measurement?.bearing
  val bearingAccuracy = measurement?.bearingAccuracy
  val bearingPainter = rememberBearingPainter(sizes, colors)
  val positionAccuracy = location?.horizontalAccuracy
  val locationSource = rememberLocationSource(measurement, oldLocationThreshold)
  val isOldLocation = feature["isOldLocation"].asBoolean(const(false))

  CircleLayer(
    id = "$idPrefix-accuracy",
    source = locationSource,
    visible = positionAccuracy != null && positionAccuracy > accuracyThreshold,
    radius =
      switch(
        condition(test = isOldLocation, output = const(0.dp)),
        fallback =
          (feature["accuracy"].asNumber() / const((viewport?.metersPerDpAtTarget ?: 0.0).toFloat()))
            .dp,
      ),
    color = const(colors.accuracyFillColor),
    strokeColor = const(colors.accuracyStrokeColor),
    strokeWidth = const(sizes.accuracyStrokeWidth),
    pitchAlignment = const(CirclePitchAlignment.Map),
  )

  CircleLayer(
    id = "$idPrefix-shadow",
    source = locationSource,
    visible = sizes.shadowSize > 0.dp && location?.position != null,
    radius = const(sizes.dotRadius + sizes.dotStrokeWidth + sizes.shadowSize),
    color = const(colors.shadowColor),
    blur = const(sizes.shadowBlur),
    translate = const(DpOffset(0.dp, 1.dp)),
    pitchAlignment = const(CirclePitchAlignment.Map),
  )

  CircleLayer(
    id = "$idPrefix-dot",
    source = locationSource,
    visible = location?.position != null,
    radius = const(sizes.dotRadius),
    color =
      switch(
        condition(test = isOldLocation, output = const(colors.dotFillColorOldLocation)),
        fallback = const(colors.dotFillColorCurrentLocation),
      ),
    strokeColor = const(colors.dotStrokeColor),
    strokeWidth = const(sizes.dotStrokeWidth),
    onClick = {
      location?.let { onClick?.invoke(it) }
      ClickResult.Consume
    },
    onLongClick = {
      location?.let { onLongClick?.invoke(it) }
      ClickResult.Consume
    },
    pitchAlignment = const(CirclePitchAlignment.Map),
  )

  SymbolLayer(
    id = "$idPrefix-bearing",
    source = locationSource,
    visible = bearing != null,
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

  if (bearing != null && bearingAccuracy != null) {
    val bearingAccuracyPainter =
      rememberBearingAccuracyPainter(
        sizes = sizes,
        colors = colors,
        bearingAccuracy = bearingAccuracy,
      )

    SymbolLayer(
      id = "$idPrefix-bearingAccuracy",
      source = locationSource,
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
}

internal fun defaultBearingAccuracy(location: LocationMeasurement?, bearing: Bearing?): Rotation? =
  location?.courseAccuracy.takeIf { bearing == location?.course }

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
  bearingAccuracy: Rotation,
): VectorPainter {
  val density by rememberUpdatedState(LocalDensity.current)

  val dotRadius by rememberUpdatedState(sizes.dotRadius)
  val dotStrokeWidth by rememberUpdatedState(sizes.dotStrokeWidth)
  val bearingColor by rememberUpdatedState(colors.bearingColor)

  val bearingAccuracy by rememberUpdatedState(bearingAccuracy)

  val bearingAccuracyVector by remember {
    derivedStateOf {
      val radius = with(density) { Offset(dotRadius.toPx(), dotRadius.toPx()) }

      val deltaDegrees = 2 * bearingAccuracy.inDegrees
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
private fun rememberLocationSource(
  measurement: LocationPuckMeasurement?,
  oldLocationThreshold: Duration = 30.seconds,
): GeoJsonSource {
  val isOldLocation = rememberIsLocationOld(oldLocationThreshold, measurement?.measurementMark)
  val features =
    remember(measurement, isOldLocation) { locationFeatures(measurement, isOldLocation) }

  return rememberGeoJsonSource(GeoJsonData.Features(features))
}

internal fun locationFeatures(
  measurement: LocationPuckMeasurement?,
  isOldLocation: Boolean,
) =
  if (measurement == null) {
    FeatureCollection()
  } else {
    val location = measurement.location
    FeatureCollection(
      Feature(
        geometry = Point(location.position),
        properties =
          buildJsonObject {
            put("accuracy", location.horizontalAccuracy?.inMeters)
            put("bearing", measurement.bearing?.let { (it - Bearing.North).inDegrees })
            put("bearingAccuracy", measurement.bearingAccuracy?.inDegrees)
            put("isOldLocation", isOldLocation)
          },
      )
    )
  }

internal data class LocationPuckMeasurement(
  val location: LocationMeasurement,
  val measurementMark: TimeMark?,
  val bearing: Bearing?,
  val bearingAccuracy: Rotation?,
)

private fun locationPuckMeasurement(
  location: LocationMeasurement?,
  measurementMark: TimeMark?,
  bearing: Bearing?,
  bearingAccuracy: Rotation?,
): LocationPuckMeasurement? = location?.let {
  LocationPuckMeasurement(it, measurementMark, bearing, bearingAccuracy)
}

@Composable
internal fun rememberIsLocationOld(
  oldLocationThreshold: Duration,
  measurementMark: TimeMark?,
): Boolean {
  var isOld by
    remember(measurementMark, oldLocationThreshold) {
      mutableStateOf(measurementMark?.elapsedNow()?.let { it > oldLocationThreshold } == true)
    }
  LaunchedEffect(measurementMark, oldLocationThreshold) {
    if (measurementMark == null || isOld) return@LaunchedEffect

    val remaining = oldLocationThreshold - measurementMark.elapsedNow()
    if (remaining.isInfinite()) return@LaunchedEffect
    if (remaining > Duration.ZERO) delay(remaining)
    isOld = true
  }
  return isOld
}

public typealias LocationClickHandler = (LocationMeasurement) -> Unit
