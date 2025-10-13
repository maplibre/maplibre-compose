package org.maplibre.compose.layers

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
import kotlin.time.times
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
import org.maplibre.compose.location.Location
import org.maplibre.compose.location.UserLocationState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult

@Composable
public fun BasicLocationLayer(
  id: String,
  locationState: UserLocationState,
  cameraState: CameraState,
  updateInterval: Duration = 1.seconds,
  oldLocationThreshold: Duration = 2 * updateInterval,
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
  showBearingAccuracy: Boolean = true,
  bearingSize: Dp = 6.dp,
  bearingColor: Color = Color.Red,
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
                "age" to JsonPrimitive(location.age.inWholeNanoseconds.toFloat()),
              ),
          )
        )
      }
    }

  val locationSource = rememberGeoJsonSource(GeoJsonData.Features(features))

  CircleLayer(
    id = "$id-accuracy",
    source = locationSource,
    visible = locationState.location.let { it != null && it.accuracy > accuracyThreshold },
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
    id = "$id-shadow",
    source = locationSource,
    visible = locationState.location != null,
    radius = const(dotRadius + 2 * shadowSize),
    color = const(shadowColor),
    blur = const(shadowBlur),
    translate = const(DpOffset(0.dp, 1.dp)),
  )

  CircleLayer(
    id = "$id-dot",
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
    id = "$id-bearing",
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
    id = "$id-bearingAccuracy",
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
