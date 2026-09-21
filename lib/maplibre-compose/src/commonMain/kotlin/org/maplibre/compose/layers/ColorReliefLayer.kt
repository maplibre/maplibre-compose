package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.RasterResampling
import org.maplibre.compose.sources.RasterDemTileSource
import org.maplibre.compose.style.TransitionOptions
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
 * @param opacityTransition Timing for changes to [opacity]. Null uses the style's global
 *   transition.
 * @param resampling The resampling/interpolation method to use for overscaling, also known as
 *   texture magnification filter.
 *
 *   Not yet supported on native.
 */
@Composable
@MaplibreComposable
public fun ColorReliefLayer(
  id: String,
  source: RasterDemTileSource,
  minZoom: Float = 0.0f,
  maxZoom: Float = 24.0f,
  visible: Boolean = true,
  color: Expression<ColorValue> = LayerDefaults.ColorReliefColors,
  opacity: Expression<FloatValue> = const(1f),
  opacityTransition: TransitionOptions? = null,
  resampling: Expression<RasterResampling>? = null,
) {

  Layer(
    id = id,
    source = source,
    definition =
      builtInLayerDefinition("color-relief") {
        root("minzoom", JsonPrimitive(minZoom))
        root("maxzoom", JsonPrimitive(maxZoom))
        layout("visibility", JsonPrimitive(if (visible) "visible" else "none"))
        paint("color-relief-color", color)
        paint("color-relief-opacity", opacity)
        paintTransition("color-relief-opacity", opacityTransition)
        paint("resampling", resampling)
      },
    onClick = null,
    onLongClick = null,
  )
}
