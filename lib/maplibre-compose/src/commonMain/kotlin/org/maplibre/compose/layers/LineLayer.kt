package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.expressions.value.VectorValue
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.SourceReferenceEffect
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MaplibreComposable

/**
 * A line layer draws polylines and polygons from the [sourceLayer] in the given [source] in the
 * given style as a series of lines and outlines, respectively. If nothing else is specified, these
 * will be black lines of 1 dp width.
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
 * @param sortKey Sorts features within this layer in ascending order based on this value. Features
 *   with a higher sort key will appear above features with a lower sort key. The expression may use
 *   feature properties.
 * @param translate The geometry's offset relative to the [translateAnchor]. Negative numbers
 *   indicate left and up, respectively.
 * @param translateTransition Timing for changes to [translate]. Null uses the style's global
 *   transition.
 * @param translateAnchor Frame of reference for offsetting geometry.
 *
 *   Ignored if [translate] is not set.
 *
 * @param opacity Lines opacity. A value in range `[0..1]`. The expression may use feature
 *   properties and feature state.
 * @param opacityTransition Timing for changes to [opacity]. Null uses the style's global
 *   transition.
 * @param layerOpacity Opacity applied to the layer as a whole. Unlike [opacity] and the alpha of
 *   [color], which apply per feature and accumulate where lines overlap, this value is applied once
 *   so overlapping lines appear as a single surface. A value in range `[0..1]`.
 *
 *   Not yet supported on native
 *   ([maplibre-native#4298](https://github.com/maplibre/maplibre-native/issues/4298)).
 *
 * @param layerOpacityTransition Timing for changes to [layerOpacity]. Null uses the style's global
 *   transition.
 *
 *   Not yet supported on native
 *   ([maplibre-native#4298](https://github.com/maplibre/maplibre-native/issues/4298)).
 *
 * @param color Lines color. The expression may use feature properties and feature state.
 *
 *   Ignored if [pattern] is specified.
 *
 * @param colorTransition Timing for changes to [color]. Null uses the style's global transition.
 * @param dasharray Specifies the lengths of the alternating dashes and gaps that form the dash
 *   pattern. The lengths are later scaled by the line width. To convert a dash length to pixels,
 *   multiply the length by the current line width. Note that GeoJSON sources with `lineMetrics =
 *   true` specified won't render dashed lines to the expected scale. Also note that zoom-dependent
 *   expressions will be evaluated only at integer zoom levels. The expression may use feature
 *   properties. Ignored if [pattern] is specified.
 * @param dasharrayTransition Timing for changes to [dasharray]. Null uses the style's global
 *   transition.
 * @param pattern Image to use for drawing image lines. For seamless patterns, image width must be a
 *   factor of two (2, 4, 8, ..., 512). Note that zoom-dependent expressions will be evaluated only
 *   at integer zoom levels. The expression may use feature properties.
 * @param patternTransition Timing for changes to [pattern]. Null uses the style's global
 *   transition.
 * @param gradient Defines a gradient with which to color a line feature. Can only be used with
 *   GeoJSON sources that specify `lineMetrics = true`.
 *
 *   Ignored if [pattern] or [dasharray] is specified.
 *
 * @param blur Blur applied to the lines. The expression may use feature properties and feature
 *   state.
 * @param blurTransition Timing for changes to [blur]. Null uses the style's global transition.
 * @param width Thickness of the lines' stroke. The expression may use feature properties and
 *   feature state.
 * @param widthTransition Timing for changes to [width]. Null uses the style's global transition.
 * @param gapWidth If not `0`, instead of one, two lines, each left and right of each line's actual
 *   path are drawn, with the given gap in-between them. The expression may use feature properties
 *   and feature state.
 * @param gapWidthTransition Timing for changes to [gapWidth]. Null uses the style's global
 *   transition.
 * @param offset The lines' offset. For linear features, a positive value offsets the line to the
 *   right, relative to the direction of the line, and a negative value to the left. For polygon
 *   features, a positive value results in an inset, and a negative value results in an outset. The
 *   expression may use feature properties and feature state.
 * @param offsetTransition Timing for changes to [offset]. Null uses the style's global transition.
 * @param cap Display of line endings. The expression may use feature properties.
 * @param join Display of joined lines. The expression may use feature properties.
 * @param miterLimit Limit at which to automatically convert to bevel join for sharp angles when
 *   [join] is [LineJoin.Miter]. The expression may use feature properties.
 * @param roundLimit Limit at which to automatically convert to miter join for sharp angles when
 *   [join] is [LineJoin.Round]. The expression may use feature properties.
 * @param onClick Function to call when any feature in this layer has been clicked.
 * @param onLongClick Function to call when any feature in this layer has been long-clicked.
 */
@Composable
@MaplibreComposable
public fun LineLayer(
  id: String,
  source: Source,
  sourceLayer: String = "",
  minZoom: Float = 0.0f,
  maxZoom: Float = 24.0f,
  filter: Expression<BooleanValue> = nil(),
  visible: Boolean = true,
  sortKey: Expression<FloatValue> = nil(),
  translate: Expression<DpOffsetValue> = const(DpOffset.Zero),
  translateTransition: TransitionOptions? = null,
  translateAnchor: Expression<TranslateAnchor> = const(TranslateAnchor.Map),
  opacity: Expression<FloatValue> = const(1f),
  opacityTransition: TransitionOptions? = null,
  layerOpacity: Expression<FloatValue> = nil(),
  layerOpacityTransition: TransitionOptions? = null,
  color: Expression<ColorValue> = const(Color.Black),
  colorTransition: TransitionOptions? = null,
  dasharray: Expression<VectorValue<Number>> = nil(),
  dasharrayTransition: TransitionOptions? = null,
  pattern: Expression<ImageValue> = nil(),
  patternTransition: TransitionOptions? = null,
  gradient: Expression<ColorValue> = nil(),
  blur: Expression<DpValue> = const(0.dp),
  blurTransition: TransitionOptions? = null,
  width: Expression<DpValue> = const(1.dp),
  widthTransition: TransitionOptions? = null,
  gapWidth: Expression<DpValue> = const(0.dp),
  gapWidthTransition: TransitionOptions? = null,
  offset: Expression<DpValue> = const(0.dp),
  offsetTransition: TransitionOptions? = null,
  cap: Expression<LineCap> = const(LineCap.Butt),
  join: Expression<LineJoin> = const(LineJoin.Miter),
  miterLimit: Expression<FloatValue> = const(2f),
  roundLimit: Expression<FloatValue> = const(1.05f),
  onClick: FeaturesClickHandler? = null,
  onLongClick: FeaturesClickHandler? = null,
) {
  val compile = rememberPropertyCompiler()

  val compiledFilter = compile(filter)
  val compiledSortKey = compile(sortKey)
  val compiledTranslate = compile(translate)
  val compiledTranslateAnchor = compile(translateAnchor)
  val compiledOpacity = compile(opacity)
  val compiledLayerOpacity = compile(layerOpacity)
  val compiledColor = compile(color)
  val compiledDasharray = compile(dasharray)
  val compiledPattern = compile(pattern)
  val compiledGradient = compile(gradient)
  val compiledBlur = compile(blur)
  val compiledWidth = compile(width)
  val compiledGapWidth = compile(gapWidth)
  val compiledOffset = compile(offset)
  val compiledCap = compile(cap)
  val compiledJoin = compile(join)
  val compiledMiterLimit = compile(miterLimit)
  val compiledRoundLimit = compile(roundLimit)

  SourceReferenceEffect(source)
  LayerNode(
    factory = { LineLayer(id = id, source = source) },
    recreateKey = sourceLayer,
    update = {
      set(sourceLayer) { layer.sourceLayer = it }
      set(minZoom) { layer.minZoom = it }
      set(maxZoom) { layer.maxZoom = it }
      set(compiledFilter) { layer.setFilter(it) }
      set(visible) { layer.visible = it }
      set(compiledCap) { layer.setLineCap(it) }
      set(compiledJoin) { layer.setLineJoin(it) }
      set(compiledMiterLimit) { layer.setLineMiterLimit(it) }
      set(compiledRoundLimit) { layer.setLineRoundLimit(it) }
      set(compiledSortKey) { layer.setLineSortKey(it) }
      set(compiledOpacity) { layer.setLineOpacity(it) }
      set(opacityTransition) { layer.setLineOpacityTransition(it) }
      set(compiledLayerOpacity) { layer.setLineLayerOpacity(it) }
      set(layerOpacityTransition) { layer.setLineLayerOpacityTransition(it) }
      set(compiledColor) { layer.setLineColor(it) }
      set(colorTransition) { layer.setLineColorTransition(it) }
      set(compiledTranslate) { layer.setLineTranslate(it) }
      set(translateTransition) { layer.setLineTranslateTransition(it) }
      set(compiledTranslateAnchor) { layer.setLineTranslateAnchor(it) }
      set(compiledWidth) { layer.setLineWidth(it) }
      set(widthTransition) { layer.setLineWidthTransition(it) }
      set(compiledGapWidth) { layer.setLineGapWidth(it) }
      set(gapWidthTransition) { layer.setLineGapWidthTransition(it) }
      set(compiledOffset) { layer.setLineOffset(it) }
      set(offsetTransition) { layer.setLineOffsetTransition(it) }
      set(compiledBlur) { layer.setLineBlur(it) }
      set(blurTransition) { layer.setLineBlurTransition(it) }
      set(compiledDasharray) { layer.setLineDasharray(it) }
      set(dasharrayTransition) { layer.setLineDasharrayTransition(it) }
      set(compiledPattern) { layer.setLinePattern(it) }
      set(patternTransition) { layer.setLinePatternTransition(it) }
      set(compiledGradient) { layer.setLineGradient(it) }
    },
    onClick = onClick,
    onLongClick = onLongClick,
  )
}

internal class LineLayer(id: String, source: Source) : FeatureLayer(id, source) {

  override val type: String = "line"

  override var sourceLayer: String = ""
    set(value) {
      field = value
      setSourceLayerProperty(value)
    }

  override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    setFilterExpression(filter)
  }

  fun setLineCap(cap: CompiledExpression<LineCap>) {
    setLayoutProperty("line-cap", cap)
  }

  fun setLineJoin(join: CompiledExpression<LineJoin>) {
    setLayoutProperty("line-join", join)
  }

  fun setLineMiterLimit(miterLimit: CompiledExpression<FloatValue>) {
    setLayoutProperty("line-miter-limit", miterLimit)
  }

  fun setLineRoundLimit(roundLimit: CompiledExpression<FloatValue>) {
    setLayoutProperty("line-round-limit", roundLimit)
  }

  fun setLineSortKey(sortKey: CompiledExpression<FloatValue>) {
    setLayoutProperty("line-sort-key", sortKey)
  }

  fun setLineOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("line-opacity", opacity)
  }

  fun setLineOpacityTransition(options: TransitionOptions?) {
    setPaintTransition("line-opacity", options)
  }

  fun setLineLayerOpacity(layerOpacity: CompiledExpression<FloatValue>) {
    setPaintProperty("line-layer-opacity", layerOpacity)
  }

  fun setLineLayerOpacityTransition(options: TransitionOptions?) {
    setPaintTransition("line-layer-opacity", options)
  }

  fun setLineColor(color: CompiledExpression<ColorValue>) {
    setPaintProperty("line-color", color)
  }

  fun setLineColorTransition(options: TransitionOptions?) {
    setPaintTransition("line-color", options)
  }

  fun setLineTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProperty("line-translate", translate)
  }

  fun setLineTranslateTransition(options: TransitionOptions?) {
    setPaintTransition("line-translate", options)
  }

  fun setLineTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    setPaintProperty("line-translate-anchor", translateAnchor)
  }

  fun setLineWidth(width: CompiledExpression<DpValue>) {
    setPaintProperty("line-width", width)
  }

  fun setLineWidthTransition(options: TransitionOptions?) {
    setPaintTransition("line-width", options)
  }

  fun setLineGapWidth(gapWidth: CompiledExpression<DpValue>) {
    setPaintProperty("line-gap-width", gapWidth)
  }

  fun setLineGapWidthTransition(options: TransitionOptions?) {
    setPaintTransition("line-gap-width", options)
  }

  fun setLineOffset(offset: CompiledExpression<DpValue>) {
    setPaintProperty("line-offset", offset)
  }

  fun setLineOffsetTransition(options: TransitionOptions?) {
    setPaintTransition("line-offset", options)
  }

  fun setLineBlur(blur: CompiledExpression<DpValue>) {
    setPaintProperty("line-blur", blur)
  }

  fun setLineBlurTransition(options: TransitionOptions?) {
    setPaintTransition("line-blur", options)
  }

  fun setLineDasharray(dasharray: CompiledExpression<VectorValue<Number>>) {
    setPaintProperty("line-dasharray", dasharray)
  }

  fun setLineDasharrayTransition(options: TransitionOptions?) {
    setPaintTransition("line-dasharray", options)
  }

  fun setLinePattern(pattern: CompiledExpression<ImageValue>) {
    setPaintProperty("line-pattern", pattern)
  }

  fun setLinePatternTransition(options: TransitionOptions?) {
    setPaintTransition("line-pattern", options)
  }

  fun setLineGradient(gradient: CompiledExpression<ColorValue>) {
    setPaintProperty("line-gradient", gradient)
  }
}
