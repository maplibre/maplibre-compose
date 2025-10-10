package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.FeatureCollection
import io.github.dellisd.spatialk.geojson.Point
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
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.location.Location
import org.maplibre.compose.location.UserLocationState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource

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
  onClick: LocationClickHandler? = null,
  onLongClick: LocationClickHandler? = null,
) {
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
  )

  // TODO: symbol layer for bearing
}

public typealias LocationClickHandler = (Location) -> Unit
