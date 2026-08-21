package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.SourceReferenceEffect
import org.maplibre.compose.util.MaplibreComposable

/**
 * Client-side elevation coloring (hypsometric tinting) based on DEM data. The implementation
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
) {
  // The style spec also defines `resampling`, but MapLibre Native refuses the whole layer when the
  // property is present, so it is not exposed until Native accepts it.
  val compile = rememberPropertyCompiler()

  val compiledColor = compile(color)
  val compiledOpacity = compile(opacity)

  SourceReferenceEffect(source)
  LayerNode(
    factory = { ColorReliefLayer(id = id, source = source) },
    update = {
      set(minZoom) { layer.minZoom = it }
      set(maxZoom) { layer.maxZoom = it }
      set(visible) { layer.visible = it }
      set(compiledColor) { layer.setColorReliefColor(it) }
      set(compiledOpacity) { layer.setColorReliefOpacity(it) }
    },
    onClick = null,
    onLongClick = null,
  )
}

internal expect class ColorReliefLayer(id: String, source: Source) : Layer {
  val source: Source

  fun setColorReliefColor(color: CompiledExpression<ColorValue>)

  fun setColorReliefOpacity(opacity: CompiledExpression<FloatValue>)
}
