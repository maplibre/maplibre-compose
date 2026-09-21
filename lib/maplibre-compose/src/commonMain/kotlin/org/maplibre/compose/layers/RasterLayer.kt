package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.MillisecondsValue
import org.maplibre.compose.expressions.value.RasterResampling
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable

/**
 * Raster map textures such as satellite imagery.
 *
 * @param id Unique layer name.
 * @param source Raster tile or image source for this layer.
 * @param minZoom The minimum zoom level for the layer. At zoom levels less than this, the layer
 *   will be hidden. A value in the range of `[0..24]`.
 * @param maxZoom The maximum zoom level for the layer. At zoom levels equal to or greater than
 *   this, the layer will be hidden. A value in the range of `[0..24]`.
 * @param visible Whether the layer should be displayed.
 * @param opacity The opacity at which the texture will be drawn. A value in range `[0..1]`.
 * @param opacityTransition Timing for changes to [opacity]. Null uses the style's global
 *   transition.
 * @param hueRotate Rotates hues around the color wheel. Unit in degrees, i.e. a value in range
 *   `[0..360)`.
 * @param hueRotateTransition Timing for changes to [hueRotate]. Null uses the style's global
 *   transition.
 * @param brightnessMin Increase or reduce the brightness of the image. The value is the minimum
 *   brightness. A value in range `[0..1]`.
 * @param brightnessMinTransition Timing for changes to [brightnessMin]. Null uses the style's
 *   global transition.
 * @param brightnessMax Increase or reduce the brightness of the image. The value is the maximum
 *   brightness. A value in range `[0..1]`.
 * @param brightnessMaxTransition Timing for changes to [brightnessMax]. Null uses the style's
 *   global transition.
 * @param saturation Increase or reduce the saturation of the image. A value in range `[-1..1]`.
 * @param saturationTransition Timing for changes to [saturation]. Null uses the style's global
 *   transition.
 * @param contrast Increase or reduce the contrast of the image. A value in range `[-1..1]`.
 * @param contrastTransition Timing for changes to [contrast]. Null uses the style's global
 *   transition.
 * @param resampling The resampling/interpolation method to use for overscaling, also known as
 *   texture magnification filter.
 * @param fadeDuration Fade duration in milliseconds when a new tile is added, or when a video is
 *   started or its coordinates are updated. A value in range `[0..infinity)`. This times the
 *   cross-fade between tiles; the `*Transition` parameters time changes to the paint values
 *   themselves.
 */
@Composable
@MaplibreComposable
public fun RasterLayer(
  id: String,
  source: RasterSource,
  minZoom: Float = 0.0f,
  maxZoom: Float = 24.0f,
  visible: Boolean = true,
  opacity: Expression<FloatValue> = const(1f),
  opacityTransition: TransitionOptions? = null,
  hueRotate: Expression<FloatValue> = const(0f),
  hueRotateTransition: TransitionOptions? = null,
  brightnessMin: Expression<FloatValue> = const(0f),
  brightnessMinTransition: TransitionOptions? = null,
  brightnessMax: Expression<FloatValue> = const(1f),
  brightnessMaxTransition: TransitionOptions? = null,
  saturation: Expression<FloatValue> = const(0f),
  saturationTransition: TransitionOptions? = null,
  contrast: Expression<FloatValue> = const(0f),
  contrastTransition: TransitionOptions? = null,
  resampling: Expression<RasterResampling> = const(RasterResampling.Linear),
  fadeDuration: Expression<MillisecondsValue> = const(300.milliseconds),
) {

  Layer(
    id = id,
    source = source,
    definition =
      builtInLayerDefinition("raster") {
        root("minzoom", JsonPrimitive(minZoom))
        root("maxzoom", JsonPrimitive(maxZoom))
        layout("visibility", JsonPrimitive(if (visible) "visible" else "none"))
        paint("raster-opacity", opacity)
        paintTransition("raster-opacity", opacityTransition)
        paint("raster-hue-rotate", hueRotate)
        paintTransition("raster-hue-rotate", hueRotateTransition)
        paint("raster-brightness-min", brightnessMin)
        paintTransition("raster-brightness-min", brightnessMinTransition)
        paint("raster-brightness-max", brightnessMax)
        paintTransition("raster-brightness-max", brightnessMaxTransition)
        paint("raster-saturation", saturation)
        paintTransition("raster-saturation", saturationTransition)
        paint("raster-contrast", contrast)
        paintTransition("raster-contrast", contrastTransition)
        paint("raster-resampling", resampling)
        paint("raster-fade-duration", fadeDuration)
      },
    onClick = null,
    onLongClick = null,
  )
}
