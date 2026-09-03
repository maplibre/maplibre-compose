package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.MillisecondsValue
import org.maplibre.compose.expressions.value.RasterResampling
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.SourceReferenceEffect
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable

/**
 * Raster map textures such as satellite imagery.
 *
 * @param id Unique layer name.
 * @param source Raster data source for this layer.
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
  source: Source,
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
  val compile = rememberPropertyCompiler()

  val compiledOpacity = compile(opacity)
  val compiledHueRotate = compile(hueRotate)
  val compiledBrightnessMin = compile(brightnessMin)
  val compiledBrightnessMax = compile(brightnessMax)
  val compiledSaturation = compile(saturation)
  val compiledContrast = compile(contrast)
  val compiledResampling = compile(resampling)
  val compiledFadeDuration = compile(fadeDuration)

  SourceReferenceEffect(source)
  LayerNode(
    factory = { RasterLayer(id = id, source = source) },
    update = {
      set(minZoom) { layer.minZoom = it }
      set(maxZoom) { layer.maxZoom = it }
      set(visible) { layer.visible = it }
      set(compiledOpacity) { layer.setRasterOpacity(it) }
      set(opacityTransition) { layer.setRasterOpacityTransition(it) }
      set(compiledHueRotate) { layer.setRasterHueRotate(it) }
      set(hueRotateTransition) { layer.setRasterHueRotateTransition(it) }
      set(compiledBrightnessMin) { layer.setRasterBrightnessMin(it) }
      set(brightnessMinTransition) { layer.setRasterBrightnessMinTransition(it) }
      set(compiledBrightnessMax) { layer.setRasterBrightnessMax(it) }
      set(brightnessMaxTransition) { layer.setRasterBrightnessMaxTransition(it) }
      set(compiledSaturation) { layer.setRasterSaturation(it) }
      set(saturationTransition) { layer.setRasterSaturationTransition(it) }
      set(compiledContrast) { layer.setRasterContrast(it) }
      set(contrastTransition) { layer.setRasterContrastTransition(it) }
      set(compiledResampling) { layer.setRasterResampling(it) }
      set(compiledFadeDuration) { layer.setRasterFadeDuration(it) }
    },
    onClick = null,
    onLongClick = null,
  )
}

internal class RasterLayer(id: String, val source: Source) : Layer(id) {

  override val type: String = "raster"

  override val sourceId: String = source.id

  fun setRasterOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("raster-opacity", opacity)
  }

  fun setRasterOpacityTransition(options: TransitionOptions?) {
    setPaintTransition("raster-opacity", options)
  }

  fun setRasterHueRotate(hueRotate: CompiledExpression<FloatValue>) {
    setPaintProperty("raster-hue-rotate", hueRotate)
  }

  fun setRasterHueRotateTransition(options: TransitionOptions?) {
    setPaintTransition("raster-hue-rotate", options)
  }

  fun setRasterBrightnessMin(brightnessMin: CompiledExpression<FloatValue>) {
    setPaintProperty("raster-brightness-min", brightnessMin)
  }

  fun setRasterBrightnessMinTransition(options: TransitionOptions?) {
    setPaintTransition("raster-brightness-min", options)
  }

  fun setRasterBrightnessMax(brightnessMax: CompiledExpression<FloatValue>) {
    setPaintProperty("raster-brightness-max", brightnessMax)
  }

  fun setRasterBrightnessMaxTransition(options: TransitionOptions?) {
    setPaintTransition("raster-brightness-max", options)
  }

  fun setRasterSaturation(saturation: CompiledExpression<FloatValue>) {
    setPaintProperty("raster-saturation", saturation)
  }

  fun setRasterSaturationTransition(options: TransitionOptions?) {
    setPaintTransition("raster-saturation", options)
  }

  fun setRasterContrast(contrast: CompiledExpression<FloatValue>) {
    setPaintProperty("raster-contrast", contrast)
  }

  fun setRasterContrastTransition(options: TransitionOptions?) {
    setPaintTransition("raster-contrast", options)
  }

  fun setRasterResampling(resampling: CompiledExpression<RasterResampling>) {
    setPaintProperty("raster-resampling", resampling)
  }

  fun setRasterFadeDuration(fadeDuration: CompiledExpression<MillisecondsValue>) {
    setPaintProperty("raster-fade-duration", fadeDuration)
  }
}
