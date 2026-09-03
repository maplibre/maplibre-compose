package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.SourceReferenceEffect
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MaplibreComposable

/**
 * A fill extrusion layer draws polygons from the [sourceLayer] in the given [source] in the given
 * style as a series of extruded polygon fills, i.e. a polygon with a certain extent on the z-axis.
 * If nothing else is specified, these 3D polygons will be black and flat.
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
 * @param roundedCornerDistance The distance in meters from each fill extrusion corner, measured
 *   along the adjacent edges, that is replaced by a rounded corner. A value in the range of
 *   `[0..infinity)`. A value of `0` leaves corners sharp.
 * @param translate The geometry's offset relative to the [translateAnchor]. Negative numbers
 *   indicate left and up (on the flat plane), respectively.
 * @param translateTransition Timing for changes to [translate]. Null uses the style's global
 *   transition.
 * @param translateAnchor Frame of reference for offsetting geometry.
 *
 *   Ignored if [translate] is not set.
 *
 * @param opacity The opacity of the entire fill extrusion layer. This is rendered on a per-layer,
 *   not per-feature, basis, and data-driven styling is not available. A value in range `[0..1]`.
 * @param opacityTransition Timing for changes to [opacity]. Null uses the style's global
 *   transition.
 * @param color The base color of the extruded fill. The extrusion's surfaces will be shaded
 *   differently based on this color in combination with the style light, which
 *   [org.maplibre.compose.map.MapStyleState.light] reads and writes. The alpha component of the
 *   specified color is ignored. The expression may use feature properties and feature state.
 *   Ignored if [pattern] is specified.
 * @param colorTransition Timing for changes to [color]. Null uses the style's global transition.
 * @param pattern Name of image in sprite to use for drawing images on extruded fills. For seamless
 *   patterns, image width and height must be a factor of two (2, 4, 8, ..., 512). Note that
 *   zoom-dependent expressions will be evaluated only at integer zoom levels. The expression may
 *   use feature properties.
 * @param patternTransition Timing for changes to [pattern]. Null uses the style's global
 *   transition.
 * @param height The height in meters with which to extrude the geometries, i.e. the upper end of
 *   the 3D polygon. A value in the range of `[0..infinity)`. The expression may use feature
 *   properties and feature state.
 * @param heightTransition Timing for changes to [height]. Null uses the style's global transition.
 * @param base The height in meters with which to extrude the base of the geometries, i.e. the lower
 *   end of the 3D polygon. A value in the range of `[0..infinity)`. Must be less than or equal to
 *   [height]. The expression may use feature properties and feature state.
 * @param baseTransition Timing for changes to [base]. Null uses the style's global transition.
 * @param verticalGradient Whether to apply a vertical gradient to the sides of this layer. If
 *   `true`, sides will be shaded slightly darker farther down.
 * @param onClick Function to call when any feature in this layer has been clicked.
 * @param onLongClick Function to call when any feature in this layer has been long-clicked.
 */
@Composable
@MaplibreComposable
public fun FillExtrusionLayer(
  id: String,
  source: Source,
  sourceLayer: String = "",
  minZoom: Float = 0.0f,
  maxZoom: Float = 24.0f,
  filter: Expression<BooleanValue> = nil(),
  visible: Boolean = true,
  roundedCornerDistance: Expression<FloatValue> = nil(),
  translate: Expression<DpOffsetValue> = const(DpOffset.Zero),
  translateTransition: TransitionOptions? = null,
  translateAnchor: Expression<TranslateAnchor> = const(TranslateAnchor.Map),
  opacity: Expression<FloatValue> = const(1f),
  opacityTransition: TransitionOptions? = null,
  color: Expression<ColorValue> = const(Color.Black),
  colorTransition: TransitionOptions? = null,
  pattern: Expression<ImageValue> = nil(),
  patternTransition: TransitionOptions? = null,
  height: Expression<FloatValue> = const(0f),
  heightTransition: TransitionOptions? = null,
  base: Expression<FloatValue> = const(0f),
  baseTransition: TransitionOptions? = null,
  verticalGradient: Expression<BooleanValue> = const(true),
  onClick: FeaturesClickHandler? = null,
  onLongClick: FeaturesClickHandler? = null,
) {
  val compile = rememberPropertyCompiler()

  val compiledFilter = compile(filter)
  val compiledOpacity = compile(opacity)
  val compiledColor = compile(color)
  val compiledTranslate = compile(translate)
  val compiledTranslateAnchor = compile(translateAnchor)
  val compiledPattern = compile(pattern)
  val compiledHeight = compile(height)
  val compiledBase = compile(base)
  val compiledRoundedCornerDistance = compile(roundedCornerDistance)
  val compiledVerticalGradient = compile(verticalGradient)

  SourceReferenceEffect(source)
  LayerNode(
    factory = { FillExtrusionLayer(id = id, source = source) },
    recreateKey = sourceLayer,
    update = {
      set(sourceLayer) { layer.sourceLayer = it }
      set(minZoom) { layer.minZoom = it }
      set(maxZoom) { layer.maxZoom = it }
      set(compiledFilter) { layer.setFilter(it) }
      set(visible) { layer.visible = it }
      set(compiledRoundedCornerDistance) { layer.setFillExtrusionRoundedCornerDistance(it) }
      set(compiledTranslate) { layer.setFillExtrusionTranslate(it) }
      set(translateTransition) { layer.setFillExtrusionTranslateTransition(it) }
      set(compiledTranslateAnchor) { layer.setFillExtrusionTranslateAnchor(it) }
      set(compiledOpacity) { layer.setFillExtrusionOpacity(it) }
      set(opacityTransition) { layer.setFillExtrusionOpacityTransition(it) }
      set(compiledColor) { layer.setFillExtrusionColor(it) }
      set(colorTransition) { layer.setFillExtrusionColorTransition(it) }
      set(compiledPattern) { layer.setFillExtrusionPattern(it) }
      set(patternTransition) { layer.setFillExtrusionPatternTransition(it) }
      set(compiledHeight) { layer.setFillExtrusionHeight(it) }
      set(heightTransition) { layer.setFillExtrusionHeightTransition(it) }
      set(compiledBase) { layer.setFillExtrusionBase(it) }
      set(baseTransition) { layer.setFillExtrusionBaseTransition(it) }
      set(compiledVerticalGradient) { layer.setFillExtrusionVerticalGradient(it) }
    },
    onClick = onClick,
    onLongClick = onLongClick,
  )
}

internal class FillExtrusionLayer(id: String, source: Source) : FeatureLayer(id, source) {

  override val type: String = "fill-extrusion"

  override var sourceLayer: String = ""
    set(value) {
      field = value
      setSourceLayerProperty(value)
    }

  override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    setFilterExpression(filter)
  }

  fun setFillExtrusionOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("fill-extrusion-opacity", opacity)
  }

  fun setFillExtrusionOpacityTransition(options: TransitionOptions?) {
    setPaintTransition("fill-extrusion-opacity", options)
  }

  fun setFillExtrusionColor(color: CompiledExpression<ColorValue>) {
    setPaintProperty("fill-extrusion-color", color)
  }

  fun setFillExtrusionColorTransition(options: TransitionOptions?) {
    setPaintTransition("fill-extrusion-color", options)
  }

  fun setFillExtrusionTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProperty("fill-extrusion-translate", translate)
  }

  fun setFillExtrusionTranslateTransition(options: TransitionOptions?) {
    setPaintTransition("fill-extrusion-translate", options)
  }

  fun setFillExtrusionTranslateAnchor(anchor: CompiledExpression<TranslateAnchor>) {
    setPaintProperty("fill-extrusion-translate-anchor", anchor)
  }

  fun setFillExtrusionPattern(pattern: CompiledExpression<ImageValue>) {
    setPaintProperty("fill-extrusion-pattern", pattern)
  }

  fun setFillExtrusionPatternTransition(options: TransitionOptions?) {
    setPaintTransition("fill-extrusion-pattern", options)
  }

  fun setFillExtrusionHeight(height: CompiledExpression<FloatValue>) {
    setPaintProperty("fill-extrusion-height", height)
  }

  fun setFillExtrusionHeightTransition(options: TransitionOptions?) {
    setPaintTransition("fill-extrusion-height", options)
  }

  fun setFillExtrusionBase(base: CompiledExpression<FloatValue>) {
    setPaintProperty("fill-extrusion-base", base)
  }

  fun setFillExtrusionBaseTransition(options: TransitionOptions?) {
    setPaintTransition("fill-extrusion-base", options)
  }

  fun setFillExtrusionRoundedCornerDistance(distance: CompiledExpression<FloatValue>) {
    setLayoutProperty("fill-extrusion-rounded-corner-distance", distance)
  }

  fun setFillExtrusionVerticalGradient(verticalGradient: CompiledExpression<BooleanValue>) {
    setPaintProperty("fill-extrusion-vertical-gradient", verticalGradient)
  }
}
