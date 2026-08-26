package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.util.toStyleJson
import org.maplibre.spatialk.geojson.Position

internal class LocationIndicatorLayerDescriptor(id: String) : Layer(id) {

  override val type: String = "location-indicator"

  fun setTopImage(topImage: CompiledExpression<ImageValue>) {
    setImageProperty("top-image", topImage)
  }

  fun setBearingImage(bearingImage: CompiledExpression<ImageValue>) {
    setImageProperty("bearing-image", bearingImage)
  }

  fun setShadowImage(shadowImage: CompiledExpression<ImageValue>) {
    setImageProperty("shadow-image", shadowImage)
  }

  /**
   * MapLibre Native reads this layer's image properties with `asConstant()`, and an expression —
   * even the constant `["image", name]` wrapper the DSL compiles to — aborts the renderer with
   * `bad_variant_access` on the first frame. Only a plain image name is safe to write.
   */
  private fun setImageProperty(name: String, image: CompiledExpression<ImageValue>) {
    when (val json = image.toStyleJson()) {
      is JsonNull,
      is JsonPrimitive -> setLayoutProperty(name, json)
      is JsonArray ->
        if (
          json.size == 2 &&
            (json[0] as? JsonPrimitive)?.content == "image" &&
            json[1] is JsonPrimitive
        ) {
          setLayoutProperty(name, json[1])
        } else {
          skipUnsupportedProperty(name, image, "MapLibre Native reads only a constant image here")
        }
      else ->
        skipUnsupportedProperty(name, image, "MapLibre Native reads only a constant image here")
    }
  }

  fun setLocation(location: Position) {
    // The style property reads [latitude, longitude, altitude], unlike GeoJSON positions.
    setPaintProperty(
      "location",
      JsonArray(
        listOf(
          JsonPrimitive(location.latitude),
          JsonPrimitive(location.longitude),
          JsonPrimitive(location.altitude ?: 0.0),
        )
      ),
    )
  }

  fun setBearing(bearing: CompiledExpression<FloatValue>) {
    setPaintProperty("bearing", bearing)
  }

  fun setAccuracyRadius(accuracyRadius: CompiledExpression<FloatValue>) {
    setPaintProperty("accuracy-radius", accuracyRadius)
  }

  fun setAccuracyRadiusColor(accuracyRadiusColor: CompiledExpression<ColorValue>) {
    setPaintProperty("accuracy-radius-color", accuracyRadiusColor)
  }

  fun setAccuracyRadiusBorderColor(accuracyRadiusBorderColor: CompiledExpression<ColorValue>) {
    setPaintProperty("accuracy-radius-border-color", accuracyRadiusBorderColor)
  }

  fun setTopImageSize(topImageSize: CompiledExpression<FloatValue>) {
    setPaintProperty("top-image-size", topImageSize)
  }

  fun setBearingImageSize(bearingImageSize: CompiledExpression<FloatValue>) {
    setPaintProperty("bearing-image-size", bearingImageSize)
  }

  fun setShadowImageSize(shadowImageSize: CompiledExpression<FloatValue>) {
    setPaintProperty("shadow-image-size", shadowImageSize)
  }

  fun setImageTiltDisplacement(imageTiltDisplacement: CompiledExpression<FloatValue>) {
    setPaintProperty("image-tilt-displacement", imageTiltDisplacement)
  }

  fun setPerspectiveCompensation(perspectiveCompensation: CompiledExpression<FloatValue>) {
    setPaintProperty("perspective-compensation", perspectiveCompensation)
  }
}
