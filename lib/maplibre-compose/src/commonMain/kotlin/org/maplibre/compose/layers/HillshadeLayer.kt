package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.IlluminationAnchor
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
 * @param illuminationDirection The direction of the light source used to generate the hillshading
 *   in degrees. A value in the range of `[0..360)`. `0` means the top of the viewport or north,
 *   depending on the value of [illuminationAnchor].
 * @param illuminationAnchor Direction of light source when map is rotated. See
 *   [illuminationDirection].
 * @param exaggeration Intensity of the hillshade. A value in the range of `[0..1]`.
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
  illuminationDirection: Expression<FloatValue> = const(355f),
  illuminationAnchor: Expression<IlluminationAnchor> = const(IlluminationAnchor.Viewport),
  exaggeration: Expression<FloatValue> = const(0.5f),
) {
  val compile = rememberPropertyCompiler()

  val compiledShadowColor = compile(shadowColor)
  val compiledHighlightColor = compile(highlightColor)
  val compiledAccentColor = compile(accentColor)
  val compiledIlluminationDirection = compile(illuminationDirection)
  val compiledIlluminationAnchor = compile(illuminationAnchor)
  val compiledExaggeration = compile(exaggeration)

  SourceReferenceEffect(source)
  LayerNode(
    factory = { HillshadeLayer(id = id, source = source) },
    update = {
      set(minZoom) { layer.minZoom = it }
      set(maxZoom) { layer.maxZoom = it }
      set(visible) { layer.visible = it }
      set(compiledIlluminationDirection) { layer.setHillshadeIlluminationDirection(it) }
      set(compiledIlluminationAnchor) { layer.setHillshadeIlluminationAnchor(it) }
      set(compiledExaggeration) { layer.setHillshadeExaggeration(it) }
      set(compiledShadowColor) { layer.setHillshadeShadowColor(it) }
      set(compiledHighlightColor) { layer.setHillshadeHighlightColor(it) }
      set(compiledAccentColor) { layer.setHillshadeAccentColor(it) }
    },
    onClick = null,
    onLongClick = null,
  )
}

internal expect class HillshadeLayer(id: String, source: Source) : Layer {
  val source: Source

  fun setHillshadeIlluminationDirection(direction: CompiledExpression<FloatValue>)

  fun setHillshadeIlluminationAnchor(anchor: CompiledExpression<IlluminationAnchor>)

  fun setHillshadeExaggeration(exaggeration: CompiledExpression<FloatValue>)

  fun setHillshadeShadowColor(shadowColor: CompiledExpression<ColorValue>)

  fun setHillshadeHighlightColor(highlightColor: CompiledExpression<ColorValue>)

  fun setHillshadeAccentColor(accentColor: CompiledExpression<ColorValue>)
}
