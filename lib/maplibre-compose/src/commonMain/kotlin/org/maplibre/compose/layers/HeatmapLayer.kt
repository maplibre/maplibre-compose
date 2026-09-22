package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable

/**
 * A heatmap layer draws points from the [sourceLayer] in the given [source] as a heatmap.
 *
 * @param id Unique layer name.
 * @param source Vector data source for this layer.
 * @param sourceLayer Layer to use from the given vector tile [source].
 * @param minZoom The minimum zoom level for the layer. At zoom levels less than this, the layer
 *   will be hidden. A value in the range of `[0..24]`.
 * @param maxZoom The maximum zoom level for the layer. At zoom levels equal to or greater than
 *   this, the layer will be hidden. A value in the range of `[0..24]`.
 * @param filter An expression specifying conditions on source features. Only features that match
 *   the filter are displayed. Zoom expressions in filters are only evaluated at integer zoom
 *   levels. The expression may use feature properties. The
 *   [feature state][org.maplibre.compose.expressions.dsl.Feature.state] expression is not
 *   supported.
 * @param visible Whether the layer should be displayed.
 * @param color Defines the color of each pixel based on its density value in a heatmap. Should be
 *   an expression that uses [heatmapDensity][org.maplibre.compose.expressions.dsl.heatmapDensity]
 *   as input.
 * @param opacity The global opacity at which the heatmap layer will be drawn.
 * @param opacityTransition Timing for changes to [opacity]. Null uses the style's global
 *   transition.
 * @param radius Radius of influence of one heatmap point. Increasing the value makes the heatmap
 *   smoother, but less detailed. The expression may use feature properties and feature state.
 * @param radiusTransition Timing for changes to [radius]. Null uses the style's global transition.
 * @param weight A measure of how much an individual point contributes to the heatmap. A value of 10
 *   would be equivalent to having 10 points of weight 1 in the same spot. Especially useful when
 *   combined with clustering. A value in the range of `[0..infinity)`. The expression may use
 *   feature properties and feature state.
 * @param intensity Similar to [weight] but controls the intensity of the heatmap globally.
 *   Primarily used for adjusting the heatmap based on zoom level.
 * @param intensityTransition Timing for changes to [intensity]. Null uses the style's global
 *   transition.
 * @param onClick Function to call when any feature in this layer has been clicked.
 * @param onLongClick Called for a touch long press or secondary mouse click on this layer.
 * @param onDoubleClick Called for a double tap or double click on this layer.
 * @param hitPadding Expands tap queries to a square of this radius in dp; zero uses a point.
 */
@Composable
@MaplibreComposable
public fun HeatmapLayer(
  id: String,
  source: VectorSource,
  sourceLayer: String = "",
  minZoom: Float = 0.0f,
  maxZoom: Float = 24.0f,
  filter: Expression<BooleanValue>? = null,
  visible: Boolean = true,
  color: Expression<ColorValue> = LayerDefaults.HeatmapColors,
  opacity: Expression<FloatValue> = const(1f),
  opacityTransition: TransitionOptions? = null,
  radius: Expression<DpValue> = const(30.dp),
  radiusTransition: TransitionOptions? = null,
  weight: Expression<FloatValue> = const(1f),
  intensity: Expression<FloatValue> = const(1f),
  intensityTransition: TransitionOptions? = null,
  onClick: FeaturesClickHandler? = null,
  onLongClick: FeaturesClickHandler? = null,
  onDoubleClick: FeaturesClickHandler? = null,
  hitPadding: Dp = 0.dp,
) {

  Layer(
    id = id,
    source = source,
    type = "heatmap",
    filterUnsupportedProperties = true,
    onClick = onClick,
    onLongClick = onLongClick,
    onDoubleClick = onDoubleClick,
    hitPadding = hitPadding,
  ) {
    root("source-layer", JsonPrimitive(sourceLayer))
    root("minzoom", JsonPrimitive(minZoom))
    root("maxzoom", JsonPrimitive(maxZoom))
    root("filter", filter)
    layout("visibility", JsonPrimitive(if (visible) "visible" else "none"))
    paint("heatmap-radius", radius)
    paintTransition("heatmap-radius", radiusTransition)
    paint("heatmap-weight", weight)
    paint("heatmap-intensity", intensity)
    paintTransition("heatmap-intensity", intensityTransition)
    paint("heatmap-color", color)
    paint("heatmap-opacity", opacity)
    paintTransition("heatmap-opacity", opacityTransition)
  }
}
