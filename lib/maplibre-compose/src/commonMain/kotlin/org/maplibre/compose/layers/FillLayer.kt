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
 * A fill layer draws polygons from the [sourceLayer] in the given [source] in the given style as a
 * series of polygon fills. If nothing else is specified, these will be black.
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
 * @param opacity Fill opacity. A value in range `[0..1]`. The expression may use feature properties
 *   and feature state.
 * @param opacityTransition Timing for changes to [opacity]. Null uses the style's global
 *   transition.
 * @param layerOpacity Opacity applied to the layer as a whole. Unlike [opacity] and the alpha of
 *   [color], which apply per feature and accumulate where fills overlap, this value is applied once
 *   so overlapping fills appear as a single surface. A value in range `[0..1]`.
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
 * @param color Fill color. The expression may use feature properties and feature state.
 *
 *   Ignored if [pattern] is specified.
 *
 * @param colorTransition Timing for changes to [color]. Null uses the style's global transition.
 * @param pattern Image to use for drawing image fills. For seamless patterns, image width and
 *   height must be a factor of two (2, 4, 8, ..., 512). Note that zoom-dependent expressions will
 *   be evaluated only at integer zoom levels. The expression may use feature properties.
 * @param patternTransition Timing for changes to [pattern]. Null uses the style's global
 *   transition.
 * @param antialias Whether or not the fill should be antialiased.
 * @param outlineColor The outline color of the fill. The outline is drawn at a hairline width. The
 *   expression may use feature properties and feature state.
 *
 *   Ignored if [antialias] is `false`.
 *
 * @param outlineColorTransition Timing for changes to [outlineColor]. Defaults to
 *   [colorTransition], whether or not [outlineColor] is set. Null uses the style's global
 *   transition.
 * @param onClick Function to call when any feature in this layer has been clicked.
 * @param onLongClick Function to call when any feature in this layer has been long-clicked.
 */
@Composable
@MaplibreComposable
public fun FillLayer(
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
  pattern: Expression<ImageValue> = nil(),
  patternTransition: TransitionOptions? = null,
  antialias: Expression<BooleanValue> = const(true),
  outlineColor: Expression<ColorValue> = color,
  outlineColorTransition: TransitionOptions? = colorTransition,
  onClick: FeaturesClickHandler? = null,
  onLongClick: FeaturesClickHandler? = null,
) {
  val compile = rememberPropertyCompiler()

  val compiledFilter = compile(filter)
  val compiledSortKey = compile(sortKey)
  val compiledTranslate = compile(translate)
  val compiledAntialias = compile(antialias)
  val compiledOpacity = compile(opacity)
  val compiledLayerOpacity = compile(layerOpacity)
  val compiledColor = compile(color)
  val compiledPattern = compile(pattern)
  val compiledTranslateAnchor = compile(translateAnchor)
  val compiledOutlineColor = compile(outlineColor)

  SourceReferenceEffect(source)
  LayerNode(
    factory = { FillLayer(id = id, source = source) },
    recreateKey = sourceLayer,
    update = {
      set(sourceLayer) { layer.sourceLayer = it }
      set(minZoom) { layer.minZoom = it }
      set(maxZoom) { layer.maxZoom = it }
      set(compiledFilter) { layer.setFilter(it) }
      set(visible) { layer.visible = it }
      set(compiledSortKey) { layer.setFillSortKey(it) }
      set(compiledAntialias) { layer.setFillAntialias(it) }
      set(compiledOpacity) { layer.setFillOpacity(it) }
      set(opacityTransition) { layer.setFillOpacityTransition(it) }
      set(compiledLayerOpacity) { layer.setFillLayerOpacity(it) }
      set(layerOpacityTransition) { layer.setFillLayerOpacityTransition(it) }
      set(compiledColor) { layer.setFillColor(it) }
      set(colorTransition) { layer.setFillColorTransition(it) }
      set(compiledOutlineColor) { layer.setFillOutlineColor(it) }
      set(outlineColorTransition) { layer.setFillOutlineColorTransition(it) }
      set(compiledTranslate) { layer.setFillTranslate(it) }
      set(translateTransition) { layer.setFillTranslateTransition(it) }
      set(compiledTranslateAnchor) { layer.setFillTranslateAnchor(it) }
      set(compiledPattern) { layer.setFillPattern(it) }
      set(patternTransition) { layer.setFillPatternTransition(it) }
    },
    onClick = onClick,
    onLongClick = onLongClick,
  )
}

internal class FillLayer(id: String, source: Source) : FeatureLayer(id, source) {

  override val type: String = "fill"

  override var sourceLayer: String = ""
    set(value) {
      field = value
      setSourceLayerProperty(value)
    }

  override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    setFilterExpression(filter)
  }

  fun setFillSortKey(sortKey: CompiledExpression<FloatValue>) {
    setLayoutProperty("fill-sort-key", sortKey)
  }

  fun setFillAntialias(antialias: CompiledExpression<BooleanValue>) {
    setPaintProperty("fill-antialias", antialias)
  }

  fun setFillOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("fill-opacity", opacity)
  }

  fun setFillOpacityTransition(options: TransitionOptions?) {
    setPaintTransition("fill-opacity", options)
  }

  fun setFillLayerOpacity(layerOpacity: CompiledExpression<FloatValue>) {
    setPaintProperty("fill-layer-opacity", layerOpacity)
  }

  fun setFillLayerOpacityTransition(options: TransitionOptions?) {
    setPaintTransition("fill-layer-opacity", options)
  }

  fun setFillColor(color: CompiledExpression<ColorValue>) {
    setPaintProperty("fill-color", color)
  }

  fun setFillColorTransition(options: TransitionOptions?) {
    setPaintTransition("fill-color", options)
  }

  fun setFillOutlineColor(outlineColor: CompiledExpression<ColorValue>) {
    setPaintProperty("fill-outline-color", outlineColor)
  }

  fun setFillOutlineColorTransition(options: TransitionOptions?) {
    setPaintTransition("fill-outline-color", options)
  }

  fun setFillTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProperty("fill-translate", translate)
  }

  fun setFillTranslateTransition(options: TransitionOptions?) {
    setPaintTransition("fill-translate", options)
  }

  fun setFillTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    setPaintProperty("fill-translate-anchor", translateAnchor)
  }

  fun setFillPattern(pattern: CompiledExpression<ImageValue>) {
    setPaintProperty("fill-pattern", pattern)
  }

  fun setFillPatternTransition(options: TransitionOptions?) {
    setPaintTransition("fill-pattern", options)
  }
}
