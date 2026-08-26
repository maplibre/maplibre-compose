package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Position

/**
 * A layer that MapLibre Native renders as a location indicator: images for the position, bearing,
 * and shadow, and a circle for the position accuracy.
 *
 * This layer draws no images until at least one of [topImage], [bearingImage], or [shadowImage] is
 * set. Unlike other layers, it takes its position from [location] rather than from a source.
 *
 * MapLibre Native animates changes to [location], [bearing], and [accuracyRadius] with the style's
 * transition options, so a location update moves the indicator smoothly.
 *
 * This layer is available on Android, desktop, and iOS. MapLibre GL JS has no location indicator
 * layer, so the browser platform draws a location marker with [LocationPuck]
 * [org.maplibre.compose.location.LocationPuck] instead.
 *
 * @param id Unique layer name.
 * @param location The position to draw the indicator at.
 * @param minZoom The minimum zoom level for the layer. At zoom levels less than this, the layer
 *   will be hidden. A value in the range of `[0..24]`.
 * @param maxZoom The maximum zoom level for the layer. At zoom levels equal to or greater than
 *   this, the layer will be hidden. A value in the range of `[0..24]`.
 * @param visible Whether the layer should be displayed.
 * @param bearing The bearing of the indicator in degrees clockwise from north. Rotates
 *   [bearingImage].
 * @param accuracyRadius The position accuracy in meters, drawn as a circle under the indicator.
 * @param accuracyRadiusColor The fill color of the accuracy circle.
 * @param accuracyRadiusBorderColor The color of the accuracy circle's border.
 * @param topImage The image drawn on top, typically the location dot. See [image].
 * @param bearingImage The image drawn under [topImage] and rotated by [bearing], typically an
 *   arrow.
 * @param shadowImage The image drawn under the others, typically a shadow.
 * @param topImageSize A scale factor for [topImage].
 * @param bearingImageSize A scale factor for [bearingImage].
 * @param shadowImageSize A scale factor for [shadowImage].
 * @param imageTiltDisplacement The displacement of the images off the indicator's center when the
 *   map is pitched, in pixels, which fakes a height above the map.
 * @param perspectiveCompensation How much to compensate the perspective shrinking of the images
 *   when the map is pitched. `0` draws them shrunk with distance like map features; `1` keeps their
 *   screen size.
 */
@Composable
@MaplibreComposable
public fun LocationIndicatorLayer(
  id: String,
  location: Position,
  minZoom: Float = 0.0f,
  maxZoom: Float = 24.0f,
  visible: Boolean = true,
  bearing: Expression<FloatValue> = const(0f),
  accuracyRadius: Expression<FloatValue> = const(0f),
  accuracyRadiusColor: Expression<ColorValue> = const(Color.White),
  accuracyRadiusBorderColor: Expression<ColorValue> = const(Color.White),
  topImage: Expression<ImageValue> = nil(),
  bearingImage: Expression<ImageValue> = nil(),
  shadowImage: Expression<ImageValue> = nil(),
  topImageSize: Expression<FloatValue> = const(1f),
  bearingImageSize: Expression<FloatValue> = const(1f),
  shadowImageSize: Expression<FloatValue> = const(1f),
  imageTiltDisplacement: Expression<FloatValue> = const(0f),
  perspectiveCompensation: Expression<FloatValue> = const(0.85f),
) {
  val compile = rememberPropertyCompiler()

  val compiledBearing = compile(bearing)
  val compiledAccuracyRadius = compile(accuracyRadius)
  val compiledAccuracyRadiusColor = compile(accuracyRadiusColor)
  val compiledAccuracyRadiusBorderColor = compile(accuracyRadiusBorderColor)
  val compiledTopImage = compile(topImage)
  val compiledBearingImage = compile(bearingImage)
  val compiledShadowImage = compile(shadowImage)
  val compiledTopImageSize = compile(topImageSize)
  val compiledBearingImageSize = compile(bearingImageSize)
  val compiledShadowImageSize = compile(shadowImageSize)
  val compiledImageTiltDisplacement = compile(imageTiltDisplacement)
  val compiledPerspectiveCompensation = compile(perspectiveCompensation)

  LayerNode(
    factory = { LocationIndicatorLayerDescriptor(id = id) },
    update = {
      set(minZoom) { layer.minZoom = it }
      set(maxZoom) { layer.maxZoom = it }
      set(visible) { layer.visible = it }
      set(compiledTopImage) { layer.setTopImage(it) }
      set(compiledBearingImage) { layer.setBearingImage(it) }
      set(compiledShadowImage) { layer.setShadowImage(it) }
      set(location) { layer.setLocation(it) }
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
    onClick = null,
    onLongClick = null,
  )
}
