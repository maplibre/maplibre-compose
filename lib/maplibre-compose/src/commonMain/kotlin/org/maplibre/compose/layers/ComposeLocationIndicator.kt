package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import kotlin.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.ConstantImageExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.inDegrees
import org.maplibre.spatialk.units.extensions.inMeters

@Composable
@MaplibreComposable
internal fun ComposeLocationIndicator(properties: LocationIndicatorProperties) {
  val id = properties.id
  val location = properties.location
  val bearing = properties.bearing
  val accuracyRadius = properties.accuracyRadius
  val locationTransition = properties.locationTransition
  val bearingTransition = properties.bearingTransition
  val accuracyRadiusTransition = properties.accuracyRadiusTransition
  val history = remember { IndicatorHistory() }
  val longitude = unwrapLongitude(location.longitude, history.longitude)
  val target = Position(longitude, location.latitude, location.altitude)
  // Keep the first measurement's timing through unrelated image-loading recompositions.
  val bearingTiming =
    remember(bearing, bearingTransition) {
      if (history.bearingAvailable && bearing != null) bearingTransition
      else TransitionOptions(Duration.ZERO)
    }
  val accuracyTiming =
    remember(accuracyRadius, accuracyRadiusTransition) {
      if (history.accuracyAvailable && accuracyRadius != null) accuracyRadiusTransition
      else TransitionOptions(Duration.ZERO)
    }
  val sectorAvailable = bearing != null && properties.bearingAccuracy != null
  val sectorTiming =
    remember(
      sectorAvailable,
      properties.bearingAccuracy,
      properties.bearingAccuracyRadius,
      properties.bearingAccuracyColor,
      properties.bearingAccuracyTransition,
    ) {
      if (history.sectorAvailable && sectorAvailable) properties.bearingAccuracyTransition
      else TransitionOptions(Duration.ZERO)
    }
  SideEffect {
    history.sectorAvailable = sectorAvailable
    history.longitude = longitude
    history.bearingAvailable = bearing != null
    history.accuracyAvailable = accuracyRadius != null
  }
  Layer(
    id = id,
    definition =
      builtInLayerDefinition("location-indicator") {
        paintTransition("location", locationTransition)
        paintTransition("bearing", bearingTiming)
        paintTransition("accuracy-radius", accuracyTiming)
        paintTransition("bearing-accuracy", sectorTiming)
        paintTransition("bearing-accuracy-radius", sectorTiming)
        paintTransition("bearing-accuracy-color", sectorTiming)
        paint(
          "bearing-accuracy",
          const(
            if (sectorAvailable) properties.bearingAccuracy.inDegrees.coerceAtMost(180.0).toFloat()
            else 0f
          ),
        )
        paint("bearing-accuracy-radius", properties.bearingAccuracyRadius)
        paint("bearing-accuracy-color", properties.bearingAccuracyColor)
        root("minzoom", JsonPrimitive(properties.minZoom))
        root("maxzoom", JsonPrimitive(properties.maxZoom))
        layout("visibility", JsonPrimitive(if (properties.visible) "visible" else "none"))
        locationIndicatorImage("top-image", properties.topImage)
        locationIndicatorImage("bearing-image", properties.bearingImage.takeIf { bearing != null })
        locationIndicatorImage("shadow-image", properties.shadowImage)
        paint("location", locationIndicatorPositionJson(target))
        paint("bearing", const(bearing?.let { (it - Bearing.North).inDegrees.toFloat() } ?: 0f))
        paint("accuracy-radius", const(accuracyRadius?.inMeters?.toFloat() ?: 0f))
        paint("accuracy-radius-color", const(properties.accuracyRadiusColor))
        paint("accuracy-radius-border-color", const(properties.accuracyRadiusBorderColor))
        paint("top-image-size", const(properties.topImageSize))
        paint("bearing-image-size", const(properties.bearingImageSize))
        paint("shadow-image-size", const(properties.shadowImageSize))
        paint("image-tilt-displacement", const(properties.imageTiltDisplacement))
        paint("perspective-compensation", const(properties.perspectiveCompensation))
      },
    onClick = properties.onClick?.asFeaturesClickHandler(),
    onLongClick = properties.onLongClick?.asFeaturesClickHandler(),
    onDoubleClick = properties.onDoubleClick?.asFeaturesClickHandler(),
    hitPadding = properties.hitPadding,
  )
}

private class IndicatorHistory {
  var longitude: Double? = null
  var bearingAvailable: Boolean = false
  var accuracyAvailable: Boolean = false
  var sectorAvailable: Boolean = false
}

internal fun unwrapLongitude(longitude: Double, previous: Double?): Double {
  if (previous == null) return longitude
  val delta = ((longitude - previous + 180) % 360 + 360) % 360 - 180
  return previous + delta
}

/** The style property uses latitude, longitude, altitude rather than GeoJSON coordinate order. */
internal fun locationIndicatorPositionJson(location: Position): JsonArray =
  JsonArray(
    listOf(
      JsonPrimitive(location.latitude),
      JsonPrimitive(location.longitude),
      JsonPrimitive(location.altitude ?: 0.0),
    )
  )

internal fun LayerDefinitionBuilder.locationIndicatorImage(
  name: String,
  expression: Expression<ImageValue?>?,
) {
  val image = ConstantImageExpression(expression)
  if (image.isSupported) layout(name, image)
  else unsupported(name, "MapLibre Native reads only a constant image here")
}
