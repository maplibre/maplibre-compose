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
 *   levels. The [featureState][org.maplibre.compose.expressions.dsl.Feature.state] expression is
 *   not supported in filter expressions.
 * @param visible Whether the layer should be displayed.
 * @param sortKey Sorts features within this layer in ascending order based on this value. Features
 *   with a higher sort key will appear above features with a lower sort key.
 * @param translate The geometry's offset relative to the [translateAnchor]. Negative numbers
 *   indicate left and up, respectively.
 * @param translateAnchor Frame of reference for offsetting geometry.
 *
 *   Ignored if [translate] is not set.
 *
 * @param opacity Lines opacity. A value in range `[0..1]`.
 * @param color Lines color.
 *
 *   Ignored if [pattern] is specified.
 *
 * @param dasharray Specifies the lengths of the alternating dashes and gaps that form the dash
 *   pattern. The lengths are later scaled by the line width. To convert a dash length to pixels,
 *   multiply the length by the current line width. Note that GeoJSON sources with `lineMetrics =
 *   true` specified won't render dashed lines to the expected scale. Also note that zoom-dependent
 *   expressions will be evaluated only at integer zoom levels. Ignored if [pattern] is specified.
 * @param pattern Image to use for drawing image lines. For seamless patterns, image width must be a
 *   factor of two (2, 4, 8, ..., 512). Note that zoom-dependent expressions will be evaluated only
 *   at integer zoom levels.
 * @param gradient Defines a gradient with which to color a line feature. Can only be used with
 *   GeoJSON sources that specify `lineMetrics = true`.
 *
 *   Ignored if [pattern] or [dasharray] is specified.
 *
 * @param blur Blur applied to the lines.
 * @param width Thickness of the lines' stroke.
 * @param gapWidth If not `0`, instead of one, two lines, each left and right of each line's actual
 *   path are drawn, with the given gap in-between them.
 * @param offset The lines' offset. For linear features, a positive value offsets the line to the
 *   right, relative to the direction of the line, and a negative value to the left. For polygon
 *   features, a positive value results in an inset, and a negative value results in an outset.
 * @param cap Display of line endings.
 * @param join Display of joined lines.
 * @param miterLimit Limit at which to automatically convert to bevel join for sharp angles when
 *   [join] is [LineJoin.Miter].
 * @param roundLimit Limit at which to automatically convert to miter join for sharp angles when
 *   [join] is [LineJoin.Round].
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
  translateAnchor: Expression<TranslateAnchor> = const(TranslateAnchor.Map),
  opacity: Expression<FloatValue> = const(1f),
  color: Expression<ColorValue> = const(Color.Black),
  dasharray: Expression<VectorValue<Number>> = nil(),
  pattern: Expression<ImageValue> = nil(),
  gradient: Expression<ColorValue> = nil(),
  blur: Expression<DpValue> = const(0.dp),
  width: Expression<DpValue> = const(1.dp),
  gapWidth: Expression<DpValue> = const(0.dp),
  offset: Expression<DpValue> = const(0.dp),
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
      set(compiledColor) { layer.setLineColor(it) }
      set(compiledTranslate) { layer.setLineTranslate(it) }
      set(compiledTranslateAnchor) { layer.setLineTranslateAnchor(it) }
      set(compiledWidth) { layer.setLineWidth(it) }
      set(compiledGapWidth) { layer.setLineGapWidth(it) }
      set(compiledOffset) { layer.setLineOffset(it) }
      set(compiledBlur) { layer.setLineBlur(it) }
      set(compiledDasharray) { layer.setLineDasharray(it) }
      set(compiledPattern) { layer.setLinePattern(it) }
      set(compiledGradient) { layer.setLineGradient(it) }
    },
    onClick = onClick,
    onLongClick = onLongClick,
  )
}

internal expect class LineLayer(id: String, source: Source) : FeatureLayer {
  override var sourceLayer: String

  override fun setFilter(filter: CompiledExpression<BooleanValue>)

  fun setLineCap(cap: CompiledExpression<LineCap>)

  fun setLineJoin(join: CompiledExpression<LineJoin>)

  fun setLineMiterLimit(miterLimit: CompiledExpression<FloatValue>)

  fun setLineRoundLimit(roundLimit: CompiledExpression<FloatValue>)

  fun setLineSortKey(sortKey: CompiledExpression<FloatValue>)

  fun setLineOpacity(opacity: CompiledExpression<FloatValue>)

  fun setLineColor(color: CompiledExpression<ColorValue>)

  fun setLineTranslate(translate: CompiledExpression<DpOffsetValue>)

  fun setLineTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>)

  fun setLineWidth(width: CompiledExpression<DpValue>)

  fun setLineGapWidth(gapWidth: CompiledExpression<DpValue>)

  fun setLineOffset(offset: CompiledExpression<DpValue>)

  fun setLineBlur(blur: CompiledExpression<DpValue>)

  fun setLineDasharray(dasharray: CompiledExpression<VectorValue<Number>>)

  fun setLinePattern(pattern: CompiledExpression<ImageValue>)

  fun setLineGradient(gradient: CompiledExpression<ColorValue>)
}
