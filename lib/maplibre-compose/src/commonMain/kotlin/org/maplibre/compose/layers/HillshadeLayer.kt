package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatOrVectorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.HillshadeMethod
import org.maplibre.compose.expressions.value.IlluminationAnchor
import org.maplibre.compose.expressions.value.RasterResampling
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.SourceReferenceEffect
import org.maplibre.compose.util.MaplibreComposable

/**
 * Client-side hillshading visualization based on DEM data. The implementation supports Mapbox
 * Terrain RGB, Mapzen Terrarium tiles and custom encodings.
 *
 * @param id Unique layer name.
 * @param source Raster DEM data source for this layer.
 * @param minZoom The minimum zoom level for the layer. At zoom levels less than this, the layer
 *   will be hidden. A value in the range of `[0..24]`.
 * @param maxZoom The maximum zoom level for the layer. At zoom levels equal to or greater than
 *   this, the layer will be hidden. A value in the range of `[0..24]`.
 * @param visible Whether the layer should be displayed.
 * @param shadowColor The shading color of areas that face away from the light source.
 * @param highlightColor The shading color of areas that faces towards the light source.
 * @param accentColor The shading color used to accentuate rugged terrain like sharp cliffs and
 *   gorges.
 * @param method The hillshade algorithm to use.
 * @param illuminationDirection The direction of the light source used to generate the hillshading
 *   in degrees. A value in the range of `[0..360)`. `0` means the top of the viewport or north,
 *   depending on the value of [illuminationAnchor]. Pass a list of directions, one per light
 *   source, when [method] is [HillshadeMethod.Multidirectional].
 * @param illuminationAltitude The altitude of the light source, in degrees. `0` is sunset and `90`
 *   is noon. Pass a list of altitudes, one per light source, when [method] is
 *   [HillshadeMethod.Multidirectional].
 * @param illuminationAnchor Direction of light source when map is rotated. See
 *   [illuminationDirection].
 * @param exaggeration Intensity of the hillshade. A value in the range of `[0..1]`.
 * @param resampling The resampling/interpolation method to use for overscaling, also known as
 *   texture magnification filter.
 *
 *   Not yet supported on native
 *   ([maplibre-native#4117](https://github.com/maplibre/maplibre-native/issues/4117)).
 */
@Composable
@MaplibreComposable
public fun HillshadeLayer(
  id: String,
  source: Source,
  minZoom: Float = 0.0f,
  maxZoom: Float = 24.0f,
  visible: Boolean = true,
  shadowColor: Expression<ColorValue> = const(Color.Black),
  highlightColor: Expression<ColorValue> = const(Color.White),
  accentColor: Expression<ColorValue> = const(Color.Black),
  method: Expression<HillshadeMethod> = const(HillshadeMethod.Standard),
  illuminationDirection: Expression<FloatOrVectorValue<Number>> = const(355f),
  illuminationAltitude: Expression<FloatOrVectorValue<Number>> = const(45f),
  illuminationAnchor: Expression<IlluminationAnchor> = const(IlluminationAnchor.Viewport),
  exaggeration: Expression<FloatValue> = const(0.5f),
  resampling: Expression<RasterResampling> = nil(),
) {
  val compile = rememberPropertyCompiler()

  val compiledShadowColor = compile(shadowColor)
  val compiledHighlightColor = compile(highlightColor)
  val compiledAccentColor = compile(accentColor)
  val compiledMethod = compile(method)
  val compiledIlluminationDirection = compile(illuminationDirection)
  val compiledIlluminationAltitude = compile(illuminationAltitude)
  val compiledIlluminationAnchor = compile(illuminationAnchor)
  val compiledExaggeration = compile(exaggeration)
  val compiledResampling = compile(resampling)

  SourceReferenceEffect(source)
  LayerNode(
    factory = { HillshadeLayerDescriptor(id = id, source = source) },
    update = {
      set(minZoom) { layer.minZoom = it }
      set(maxZoom) { layer.maxZoom = it }
      set(visible) { layer.visible = it }
      set(compiledMethod) { layer.setHillshadeMethod(it) }
      set(compiledIlluminationDirection) { layer.setHillshadeIlluminationDirection(it) }
      set(compiledIlluminationAltitude) { layer.setHillshadeIlluminationAltitude(it) }
      set(compiledIlluminationAnchor) { layer.setHillshadeIlluminationAnchor(it) }
      set(compiledExaggeration) { layer.setHillshadeExaggeration(it) }
      set(compiledResampling) { layer.setResampling(it) }
      set(compiledShadowColor) { layer.setHillshadeShadowColor(it) }
      set(compiledHighlightColor) { layer.setHillshadeHighlightColor(it) }
      set(compiledAccentColor) { layer.setHillshadeAccentColor(it) }
    },
    onClick = null,
    onLongClick = null,
  )
}

internal class HillshadeLayerDescriptor(id: String, val source: Source) : Layer(id) {

  override val type: String = "hillshade"

  override val sourceId: String = source.id

  fun setHillshadeMethod(method: CompiledExpression<HillshadeMethod>) {
    setPaintProperty("hillshade-method", method)
  }

  fun setHillshadeIlluminationDirection(direction: CompiledExpression<FloatOrVectorValue<Number>>) {
    setPaintProperty("hillshade-illumination-direction", direction)
  }

  fun setHillshadeIlluminationAltitude(altitude: CompiledExpression<FloatOrVectorValue<Number>>) {
    setPaintProperty("hillshade-illumination-altitude", altitude)
  }

  fun setHillshadeIlluminationAnchor(anchor: CompiledExpression<IlluminationAnchor>) {
    setPaintProperty("hillshade-illumination-anchor", anchor)
  }

  fun setHillshadeExaggeration(exaggeration: CompiledExpression<FloatValue>) {
    setPaintProperty("hillshade-exaggeration", exaggeration)
  }

  fun setHillshadeShadowColor(shadowColor: CompiledExpression<ColorValue>) {
    setPaintProperty("hillshade-shadow-color", shadowColor)
  }

  fun setHillshadeHighlightColor(highlightColor: CompiledExpression<ColorValue>) {
    setPaintProperty("hillshade-highlight-color", highlightColor)
  }

  fun setHillshadeAccentColor(accentColor: CompiledExpression<ColorValue>) {
    setPaintProperty("hillshade-accent-color", accentColor)
  }

  fun setResampling(resampling: CompiledExpression<RasterResampling>) {
    setPaintProperty("resampling", resampling)
  }
}
