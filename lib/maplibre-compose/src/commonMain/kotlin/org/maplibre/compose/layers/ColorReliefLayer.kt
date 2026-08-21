package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.RasterResampling
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.SourceReferenceEffect
import org.maplibre.compose.util.MaplibreComposable

/**
 * Client-side elevation coloring ([hypsometric
 * tinting](https://en.wikipedia.org/wiki/Hypsometric_tints)) based on DEM data. The implementation
 * supports Mapbox Terrain RGB, Mapzen Terrarium tiles and custom encodings.
 *
 * @param id Unique layer name.
 * @param source Raster DEM data source for this layer.
 * @param minZoom The minimum zoom level for the layer. At zoom levels less than this, the layer
 *   will be hidden. A value in the range of `[0..24]`.
 * @param maxZoom The maximum zoom level for the layer. At zoom levels equal to or greater than
 *   this, the layer will be hidden. A value in the range of `[0..24]`.
 * @param visible Whether the layer should be displayed.
 * @param color Defines the color of each pixel based on its elevation. Should be an expression that
 *   uses [elevation][org.maplibre.compose.expressions.dsl.elevation] as input.
 * @param opacity The global opacity at which the color relief layer will be drawn.
 * @param resampling The resampling/interpolation method to use for overscaling, also known as
 *   texture magnification filter.
 *
 *   **Note**: Ignored with a logged warning on native platforms, which do not implement it yet;
 *   supported on the web.
 */
@Composable
@MaplibreComposable
public fun ColorReliefLayer(
  id: String,
  source: Source,
  minZoom: Float = 0.0f,
  maxZoom: Float = 24.0f,
  visible: Boolean = true,
  color: Expression<ColorValue> = LayerDefaults.ColorReliefColors,
  opacity: Expression<FloatValue> = const(1f),
  resampling: Expression<RasterResampling> = nil(),
) {
  val compile = rememberPropertyCompiler()

  val compiledColor = compile(color)
  val compiledOpacity = compile(opacity)
  val compiledResampling = compile(resampling)

  SourceReferenceEffect(source)
  LayerNode(
    factory = { ColorReliefLayer(id = id, source = source) },
    update = {
      set(minZoom) { layer.minZoom = it }
      set(maxZoom) { layer.maxZoom = it }
      set(visible) { layer.visible = it }
      set(compiledColor) { layer.setColorReliefColor(it) }
      set(compiledOpacity) { layer.setColorReliefOpacity(it) }
      set(compiledResampling) { layer.setResampling(it) }
    },
    onClick = null,
    onLongClick = null,
  )
}

internal class ColorReliefLayer(id: String, val source: Source) : Layer(id) {

  override val type: String = "color-relief"

  override val sourceId: String = source.id

  fun setColorReliefColor(color: CompiledExpression<ColorValue>) {
    setPaintProperty("color-relief-color", color)
  }

  fun setColorReliefOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("color-relief-opacity", opacity)
  }

  fun setResampling(resampling: CompiledExpression<RasterResampling>) {
    setPaintProperty("resampling", resampling)
  }
}
