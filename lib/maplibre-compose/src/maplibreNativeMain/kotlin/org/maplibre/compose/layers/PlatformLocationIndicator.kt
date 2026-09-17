package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import kotlin.time.Duration
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.inDegrees
import org.maplibre.spatialk.units.extensions.inMeters

@Composable
@MaplibreComposable
internal actual fun PlatformLocationIndicator(properties: LocationIndicatorProperties) {
  val id = properties.id
  val location = properties.location
  val bearing = properties.bearing
  val accuracyRadius = properties.accuracyRadius
  val minZoom = properties.minZoom
  val maxZoom = properties.maxZoom
  val visible = properties.visible
  val accuracyRadiusColor = properties.accuracyRadiusColor
  val accuracyRadiusBorderColor = properties.accuracyRadiusBorderColor
  val topImage = properties.topImage
  val bearingImage = properties.bearingImage
  val shadowImage = properties.shadowImage
  val topImageSize = properties.topImageSize
  val bearingImageSize = properties.bearingImageSize
  val shadowImageSize = properties.shadowImageSize
  val imageTiltDisplacement = properties.imageTiltDisplacement
  val perspectiveCompensation = properties.perspectiveCompensation
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
  SideEffect {
    history.longitude = longitude
    history.bearingAvailable = bearing != null
    history.accuracyAvailable = accuracyRadius != null
  }
  val compile = rememberPropertyCompiler()

  val compiledBearing =
    compile(const(bearing?.let { (it - Bearing.North).inDegrees.toFloat() } ?: 0f))
  val compiledAccuracyRadius = compile(const(accuracyRadius?.inMeters?.toFloat() ?: 0f))
  val compiledAccuracyRadiusColor = compile(const(accuracyRadiusColor))
  val compiledAccuracyRadiusBorderColor = compile(const(accuracyRadiusBorderColor))
  val compiledTopImage = compile(topImage)
  val compiledBearingImage = compile(bearingImage.takeIf { bearing != null })
  val compiledShadowImage = compile(shadowImage)
  val compiledTopImageSize = compile(const(topImageSize))
  val compiledBearingImageSize = compile(const(bearingImageSize))
  val compiledShadowImageSize = compile(const(shadowImageSize))
  val compiledImageTiltDisplacement = compile(const(imageTiltDisplacement))
  val compiledPerspectiveCompensation = compile(const(perspectiveCompensation))

  LayerNode(
    factory = { NativeLocationIndicatorLayer(id = id) },
    update = {
      set(locationTransition) { layer.setLocationTransition(it) }
      set(bearingTiming) { layer.setBearingTransition(it) }
      set(accuracyTiming) { layer.setAccuracyRadiusTransition(it) }
      set(minZoom) { layer.minZoom = it }
      set(maxZoom) { layer.maxZoom = it }
      set(visible) { layer.visible = it }
      set(compiledTopImage) { layer.setTopImage(it) }
      set(compiledBearingImage) { layer.setBearingImage(it) }
      set(compiledShadowImage) { layer.setShadowImage(it) }
      set(target) { layer.setLocation(it) }
      set(compiledBearing) { layer.setBearing(it) }
      set(compiledAccuracyRadius) { layer.setAccuracyRadius(it) }
      set(compiledAccuracyRadiusColor) { layer.setAccuracyRadiusColor(it) }
      set(compiledAccuracyRadiusBorderColor) { layer.setAccuracyRadiusBorderColor(it) }
      set(compiledTopImageSize) { layer.setTopImageSize(it) }
      set(compiledBearingImageSize) { layer.setBearingImageSize(it) }
      set(compiledShadowImageSize) { layer.setShadowImageSize(it) }
      set(compiledImageTiltDisplacement) { layer.setImageTiltDisplacement(it) }
      set(compiledPerspectiveCompensation) { layer.setPerspectiveCompensation(it) }
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
}

internal fun unwrapLongitude(longitude: Double, previous: Double?): Double {
  if (previous == null) return longitude
  val delta = ((longitude - previous + 180) % 360 + 360) % 360 - 180
  return previous + delta
}
