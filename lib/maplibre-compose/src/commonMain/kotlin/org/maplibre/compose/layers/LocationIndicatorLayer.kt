package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.spatialk.geojson.Position

internal class LocationIndicatorLayer(id: String) : Layer(id) {

  override val type: String = "location-indicator"

  fun setLocationTransition(options: TransitionOptions) = setPaintTransition("location", options)

  fun setBearingTransition(options: TransitionOptions) = setPaintTransition("bearing", options)

  fun setAccuracyRadiusTransition(options: TransitionOptions) =
    setPaintTransition("accuracy-radius", options)

  fun setBearingAccuracyTransition(options: TransitionOptions) =
    setPaintTransition("bearing-accuracy", options)

  fun setBearingAccuracyRadiusTransition(options: TransitionOptions) =
    setPaintTransition("bearing-accuracy-radius", options)

  fun setBearingAccuracyColorTransition(options: TransitionOptions) =
    setPaintTransition("bearing-accuracy-color", options)

  fun setBearingAccuracy(value: LayerProperty<FloatValue>) =
    setPaintProperty("bearing-accuracy", value)

  fun setBearingAccuracyRadius(value: LayerProperty<DpValue>) =
    setPaintProperty("bearing-accuracy-radius", value)

  fun setBearingAccuracyColor(value: LayerProperty<ColorValue>) =
    setPaintProperty("bearing-accuracy-color", value)

  fun setTopImage(topImage: LayerProperty<ImageValue?>) {
    setImageProperty("top-image", topImage)
  }

  fun setBearingImage(bearingImage: LayerProperty<ImageValue?>) {
    setImageProperty("bearing-image", bearingImage)
  }

  fun setShadowImage(shadowImage: LayerProperty<ImageValue?>) {
    setImageProperty("shadow-image", shadowImage)
  }

  /**
   * MapLibre Native reads this layer's image properties with `asConstant()`, and an expression —
   * even the constant `["image", name]` wrapper the DSL compiles to — aborts the renderer with
   * `bad_variant_access` on the first frame. Only a plain image name is safe to write.
   */
  private fun setImageProperty(name: String, image: LayerProperty<ImageValue?>) {
    val sample = image.resolve(image.images.associateWith { "" })
    val supported =
      sample is JsonNull ||
        sample is JsonPrimitive ||
        sample is JsonArray &&
          sample.size == 2 &&
          (sample[0] as? JsonPrimitive)?.content == "image" &&
          sample[1] is JsonPrimitive
    if (!supported) {
      skipUnsupportedProperty(name, image, "MapLibre Native reads only a constant image here")
      return
    }
    setLayoutProperty(
      name,
      LayerProperty<ImageValue?>(image.images) { resolved ->
        when (val json = image.resolve(resolved)) {
          is JsonArray -> json[1]
          else -> json
        }
      },
    )
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

  fun setBearing(bearing: LayerProperty<FloatValue>) {
    setPaintProperty("bearing", bearing)
  }

  fun setAccuracyRadius(accuracyRadius: LayerProperty<FloatValue>) {
    setPaintProperty("accuracy-radius", accuracyRadius)
  }

  fun setAccuracyRadiusColor(accuracyRadiusColor: LayerProperty<ColorValue>) {
    setPaintProperty("accuracy-radius-color", accuracyRadiusColor)
  }

  fun setAccuracyRadiusBorderColor(accuracyRadiusBorderColor: LayerProperty<ColorValue>) {
    setPaintProperty("accuracy-radius-border-color", accuracyRadiusBorderColor)
  }

  fun setTopImageSize(topImageSize: LayerProperty<FloatValue>) {
    setPaintProperty("top-image-size", topImageSize)
  }

  fun setBearingImageSize(bearingImageSize: LayerProperty<FloatValue>) {
    setPaintProperty("bearing-image-size", bearingImageSize)
  }

  fun setShadowImageSize(shadowImageSize: LayerProperty<FloatValue>) {
    setPaintProperty("shadow-image-size", shadowImageSize)
  }

  fun setImageTiltDisplacement(imageTiltDisplacement: LayerProperty<FloatValue>) {
    setPaintProperty("image-tilt-displacement", imageTiltDisplacement)
  }

  fun setPerspectiveCompensation(perspectiveCompensation: LayerProperty<FloatValue>) {
    setPaintProperty("perspective-compensation", perspectiveCompensation)
  }
}
