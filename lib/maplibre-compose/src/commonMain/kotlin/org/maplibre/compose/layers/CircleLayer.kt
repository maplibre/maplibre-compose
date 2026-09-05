package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.CirclePitchAlignment
import org.maplibre.compose.expressions.value.CirclePitchScale
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.map.HoverEvent
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.SourceReferenceEffect
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MaplibreComposable

/**
 * A circle layer draws points from the [sourceLayer] in the given [source] in the given style as a
 * circles. If nothing else is specified, these will be black dots of 5 dp radius.
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
 * @param opacity Circles opacity. A value in range `[0..1]`. The expression may use feature
 *   properties and feature state.
 * @param opacityTransition Timing for changes to [opacity]. Null uses the style's global
 *   transition.
 * @param color Circles fill color. The expression may use feature properties and feature state.
 * @param colorTransition Timing for changes to [color]. Null uses the style's global transition.
 * @param blur Amount to blur the circle. A value of `1` blurs the circle such that only the
 *   centerpoint has full opacity. The expression may use feature properties and feature state.
 * @param blurTransition Timing for changes to [blur]. Null uses the style's global transition.
 * @param radius Circles radius. The expression may use feature properties and feature state.
 * @param radiusTransition Timing for changes to [radius]. Null uses the style's global transition.
 * @param strokeOpacity Opacity of the circles' stroke. The expression may use feature properties
 *   and feature state.
 * @param strokeOpacityTransition Timing for changes to [strokeOpacity]. Null uses the style's
 *   global transition.
 * @param strokeColor Circles' stroke color. The expression may use feature properties and feature
 *   state.
 * @param strokeColorTransition Timing for changes to [strokeColor]. Null uses the style's global
 *   transition.
 * @param strokeWidth Thickness of the circles' stroke. Strokes are placed outside of the [radius].
 *   The expression may use feature properties and feature state.
 * @param strokeWidthTransition Timing for changes to [strokeWidth]. Null uses the style's global
 *   transition.
 * @param pitchScale Scaling behavior of circles when the map is pitched.
 * @param pitchAlignment Orientation of circles when the map is pitched.
 * @param onClick Function to call when any feature in this layer has been clicked.
 * @param onLongClick Function to call when any feature in this layer has been long-clicked.
 * @param onDoubleClick Called for a double tap or double click on this layer.
 * @param onTwoFingerClick Called for a two-contact tap on this layer.
 * @param hitPadding Expands tap queries to a square of this radius in dp; zero uses a point.
 * @param onHover Observes entry, movement, and exit for this layer using exact point queries.
 */
@Composable
@MaplibreComposable
public fun CircleLayer(
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
  color: Expression<ColorValue> = const(Color.Black),
  colorTransition: TransitionOptions? = null,
  blur: Expression<FloatValue> = const(0f),
  blurTransition: TransitionOptions? = null,
  radius: Expression<DpValue> = const(5.dp),
  radiusTransition: TransitionOptions? = null,
  strokeOpacity: Expression<FloatValue> = const(1f),
  strokeOpacityTransition: TransitionOptions? = null,
  strokeColor: Expression<ColorValue> = const(Color.Black),
  strokeColorTransition: TransitionOptions? = null,
  strokeWidth: Expression<DpValue> = const(0.dp),
  strokeWidthTransition: TransitionOptions? = null,
  pitchScale: Expression<CirclePitchScale> = const(CirclePitchScale.Map),
  pitchAlignment: Expression<CirclePitchAlignment> = const(CirclePitchAlignment.Viewport),
  onClick: FeaturesClickHandler? = null,
  onLongClick: FeaturesClickHandler? = null,
  onDoubleClick: FeaturesClickHandler? = null,
  onTwoFingerClick: FeaturesClickHandler? = null,
  hitPadding: Dp = 0.dp,
  onHover: ((HoverEvent) -> Unit)? = null,
) {
  val compile = rememberPropertyCompiler()

  val compiledFilter = compile(filter)
  val compiledSortKey = compile(sortKey)
  val compiledTranslate = compile(translate)
  val compiledTranslateAnchor = compile(translateAnchor)
  val compiledOpacity = compile(opacity)
  val compiledColor = compile(color)
  val compiledBlur = compile(blur)
  val compiledRadius = compile(radius)
  val compiledStrokeOpacity = compile(strokeOpacity)
  val compiledStrokeColor = compile(strokeColor)
  val compiledStrokeWidth = compile(strokeWidth)
  val compiledPitchScale = compile(pitchScale)
  val compiledPitchAlignment = compile(pitchAlignment)

  SourceReferenceEffect(source)
  LayerNode(
    factory = { CircleLayer(id = id, source = source) },
    recreateKey = sourceLayer,
    update = {
      set(sourceLayer) { layer.sourceLayer = it }
      set(minZoom) { layer.minZoom = it }
      set(maxZoom) { layer.maxZoom = it }
      set(compiledFilter) { layer.setFilter(it) }
      set(visible) { layer.visible = it }
      set(compiledSortKey) { layer.setCircleSortKey(it) }
      set(compiledRadius) { layer.setCircleRadius(it) }
      set(radiusTransition) { layer.setCircleRadiusTransition(it) }
      set(compiledColor) { layer.setCircleColor(it) }
      set(colorTransition) { layer.setCircleColorTransition(it) }
      set(compiledBlur) { layer.setCircleBlur(it) }
      set(blurTransition) { layer.setCircleBlurTransition(it) }
      set(compiledOpacity) { layer.setCircleOpacity(it) }
      set(opacityTransition) { layer.setCircleOpacityTransition(it) }
      set(compiledTranslate) { layer.setCircleTranslate(it) }
      set(translateTransition) { layer.setCircleTranslateTransition(it) }
      set(compiledTranslateAnchor) { layer.setCircleTranslateAnchor(it) }
      set(compiledPitchScale) { layer.setCirclePitchScale(it) }
      set(compiledPitchAlignment) { layer.setCirclePitchAlignment(it) }
      set(compiledStrokeWidth) { layer.setCircleStrokeWidth(it) }
      set(strokeWidthTransition) { layer.setCircleStrokeWidthTransition(it) }
      set(compiledStrokeColor) { layer.setCircleStrokeColor(it) }
      set(strokeColorTransition) { layer.setCircleStrokeColorTransition(it) }
      set(compiledStrokeOpacity) { layer.setCircleStrokeOpacity(it) }
      set(strokeOpacityTransition) { layer.setCircleStrokeOpacityTransition(it) }
    },
    onClick = onClick,
    onLongClick = onLongClick,
    onDoubleClick = onDoubleClick,
    onTwoFingerClick = onTwoFingerClick,
    hitPadding = hitPadding,
    onHover = onHover,
  )
}

internal class CircleLayer(id: String, source: Source) : FeatureLayer(id, source) {

  override val type: String = "circle"

  override var sourceLayer: String = ""
    set(value) {
      field = value
      setSourceLayerProperty(value)
    }

  override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    setFilterExpression(filter)
  }

  fun setCircleSortKey(sortKey: CompiledExpression<FloatValue>) {
    setLayoutProperty("circle-sort-key", sortKey)
  }

  fun setCircleRadius(radius: CompiledExpression<DpValue>) {
    setPaintProperty("circle-radius", radius)
  }

  fun setCircleRadiusTransition(options: TransitionOptions?) {
    setPaintTransition("circle-radius", options)
  }

  fun setCircleColor(color: CompiledExpression<ColorValue>) {
    setPaintProperty("circle-color", color)
  }

  fun setCircleColorTransition(options: TransitionOptions?) {
    setPaintTransition("circle-color", options)
  }

  fun setCircleBlur(blur: CompiledExpression<FloatValue>) {
    setPaintProperty("circle-blur", blur)
  }

  fun setCircleBlurTransition(options: TransitionOptions?) {
    setPaintTransition("circle-blur", options)
  }

  fun setCircleOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("circle-opacity", opacity)
  }

  fun setCircleOpacityTransition(options: TransitionOptions?) {
    setPaintTransition("circle-opacity", options)
  }

  fun setCircleTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProperty("circle-translate", translate)
  }

  fun setCircleTranslateTransition(options: TransitionOptions?) {
    setPaintTransition("circle-translate", options)
  }

  fun setCircleTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    setPaintProperty("circle-translate-anchor", translateAnchor)
  }

  fun setCirclePitchScale(pitchScale: CompiledExpression<CirclePitchScale>) {
    setPaintProperty("circle-pitch-scale", pitchScale)
  }

  fun setCirclePitchAlignment(pitchAlignment: CompiledExpression<CirclePitchAlignment>) {
    setPaintProperty("circle-pitch-alignment", pitchAlignment)
  }

  fun setCircleStrokeWidth(strokeWidth: CompiledExpression<DpValue>) {
    setPaintProperty("circle-stroke-width", strokeWidth)
  }

  fun setCircleStrokeWidthTransition(options: TransitionOptions?) {
    setPaintTransition("circle-stroke-width", options)
  }

  fun setCircleStrokeColor(strokeColor: CompiledExpression<ColorValue>) {
    setPaintProperty("circle-stroke-color", strokeColor)
  }

  fun setCircleStrokeColorTransition(options: TransitionOptions?) {
    setPaintTransition("circle-stroke-color", options)
  }

  fun setCircleStrokeOpacity(strokeOpacity: CompiledExpression<FloatValue>) {
    setPaintProperty("circle-stroke-opacity", strokeOpacity)
  }

  fun setCircleStrokeOpacityTransition(options: TransitionOptions?) {
    setPaintTransition("circle-stroke-opacity", options)
  }
}
