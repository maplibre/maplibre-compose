package org.maplibre.compose.demoapp.demos.ngon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.CirclePitchAlignment
import org.maplibre.compose.expressions.value.CirclePitchScale
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable

/**
 * Draws each point in [source] as a regular polygon with [corners] sides, rendered by MapLibre
 * Native's sample n-gon layer plugin. This is the typed composable a plugin integration provides on
 * top of the generic [Layer]: every paint property the plugin declares becomes a parameter with the
 * same expression types the built-in layers use, and [Layer] compiles them into the style JSON the
 * plugin reads.
 *
 * The plugin must be registered before this layer enters composition; see [NgonPlugin]. Without it,
 * the renderer rejects the layer type.
 *
 * Every paint expression may use feature properties and feature state, and every numeric or color
 * property transitions. The plugin implements feature queries, so clicks resolve to features.
 *
 * @param id Unique layer name.
 * @param source Data source; the plugin lays out point geometry only.
 * @param sourceLayer Layer to use from a vector tile [source].
 * @param minZoom The minimum zoom level for the layer, in `[0..24]`.
 * @param maxZoom The maximum zoom level for the layer, in `[0..24]`.
 * @param filter Only features matching this expression are drawn.
 * @param visible Whether the layer is displayed.
 * @param radius Circumradius of the polygon.
 * @param corners Number of sides, in `[3..360]`; the plugin rounds to a whole number.
 * @param rotate Clockwise rotation in degrees. At zero, one corner points up.
 * @param color Fill color.
 * @param blur Softens the edge; `1` fades from the center to the edge.
 * @param opacity Fill opacity in `[0..1]`.
 * @param strokeWidth Outline thickness, drawn outside the [radius].
 * @param strokeColor Outline color.
 * @param strokeOpacity Outline opacity in `[0..1]`.
 * @param translate Offset relative to [translateAnchor]; negative values move left and up.
 * @param translateAnchor Frame of reference for [translate].
 * @param pitchAlignment Whether polygons lie on the map or face the viewport when the map tilts.
 * @param pitchScale Whether polygons shrink with distance when the map tilts.
 * @param onClick Called when a polygon is clicked.
 * @param onLongClick Called for a touch long press or secondary mouse click on a polygon.
 * @param onDoubleClick Called for a double tap or double click on a polygon.
 * @param hitPadding Expands tap queries to a square of this radius in dp; zero uses a point.
 */
@Composable
@MaplibreComposable
fun NgonLayer(
  id: String,
  source: Source,
  sourceLayer: String = "",
  minZoom: Float = 0.0f,
  maxZoom: Float = 24.0f,
  filter: Expression<BooleanValue>? = null,
  visible: Boolean = true,
  radius: Expression<DpValue> = const(5.dp),
  radiusTransition: TransitionOptions? = null,
  corners: Expression<FloatValue> = const(5f),
  cornersTransition: TransitionOptions? = null,
  rotate: Expression<FloatValue> = const(0f),
  rotateTransition: TransitionOptions? = null,
  color: Expression<ColorValue> = const(Color.Black),
  colorTransition: TransitionOptions? = null,
  blur: Expression<FloatValue> = const(0f),
  blurTransition: TransitionOptions? = null,
  opacity: Expression<FloatValue> = const(1f),
  opacityTransition: TransitionOptions? = null,
  strokeWidth: Expression<DpValue> = const(0.dp),
  strokeWidthTransition: TransitionOptions? = null,
  strokeColor: Expression<ColorValue> = const(Color.Black),
  strokeColorTransition: TransitionOptions? = null,
  strokeOpacity: Expression<FloatValue> = const(1f),
  strokeOpacityTransition: TransitionOptions? = null,
  translate: Expression<DpOffsetValue> = const(DpOffset.Zero),
  translateTransition: TransitionOptions? = null,
  translateAnchor: Expression<TranslateAnchor> = const(TranslateAnchor.Map),
  pitchAlignment: Expression<CirclePitchAlignment> = const(CirclePitchAlignment.Viewport),
  pitchScale: Expression<CirclePitchScale> = const(CirclePitchScale.Map),
  onClick: FeaturesClickHandler? = null,
  onLongClick: FeaturesClickHandler? = null,
  onDoubleClick: FeaturesClickHandler? = null,
  hitPadding: Dp = 0.dp,
) {
  Layer(
    id = id,
    type = "ngon",
    source = source,
    onClick = onClick,
    onLongClick = onLongClick,
    onDoubleClick = onDoubleClick,
    hitPadding = hitPadding,
  ) {
    if (sourceLayer.isNotEmpty()) root("source-layer", JsonPrimitive(sourceLayer))
    root("minzoom", JsonPrimitive(minZoom))
    root("maxzoom", JsonPrimitive(maxZoom))
    root("filter", filter)
    layout("visibility", JsonPrimitive(if (visible) "visible" else "none"))
    paint("ngon-radius", radius)
    paintTransition("ngon-radius", radiusTransition)
    paint("ngon-corners", corners)
    paintTransition("ngon-corners", cornersTransition)
    paint("ngon-rotate", rotate)
    paintTransition("ngon-rotate", rotateTransition)
    paint("ngon-color", color)
    paintTransition("ngon-color", colorTransition)
    paint("ngon-blur", blur)
    paintTransition("ngon-blur", blurTransition)
    paint("ngon-opacity", opacity)
    paintTransition("ngon-opacity", opacityTransition)
    paint("ngon-stroke-width", strokeWidth)
    paintTransition("ngon-stroke-width", strokeWidthTransition)
    paint("ngon-stroke-color", strokeColor)
    paintTransition("ngon-stroke-color", strokeColorTransition)
    paint("ngon-stroke-opacity", strokeOpacity)
    paintTransition("ngon-stroke-opacity", strokeOpacityTransition)
    paint("ngon-translate", translate)
    paintTransition("ngon-translate", translateTransition)
    paint("ngon-translate-anchor", translateAnchor)
    paint("ngon-pitch-alignment", pitchAlignment)
    paint("ngon-pitch-scale", pitchScale)
  }
}
