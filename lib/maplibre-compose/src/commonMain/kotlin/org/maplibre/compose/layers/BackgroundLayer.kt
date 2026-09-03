package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable

/**
 * The background layer just draws the map background, by default, plain black.
 *
 * @param id Unique layer name.
 * @param minZoom The minimum zoom level for the layer. At zoom levels less than this, the layer
 *   will be hidden. A value in the range of `[0..24]`.
 * @param maxZoom The maximum zoom level for the layer. At zoom levels equal to or greater than
 *   this, the layer will be hidden. A value in the range of `[0..24]`.
 * @param visible Whether the layer should be displayed.
 * @param opacity Background opacity. A value in range `[0..1]`.
 * @param opacityTransition Timing for changes to [opacity]. Null uses the style's global
 *   transition.
 * @param color Background color.
 *
 *   Ignored if [pattern] is specified.
 *
 * @param colorTransition Timing for changes to [color]. Null uses the style's global transition.
 * @param pattern Image to use for drawing image fills. For seamless patterns, image width and
 *   height must be a factor of two (2, 4, 8, ..., 512). Note that zoom-dependent expressions will
 *   be evaluated only at integer zoom levels.
 * @param patternTransition Timing for changes to [pattern]. Null uses the style's global
 *   transition.
 */
@Composable
@MaplibreComposable
public fun BackgroundLayer(
  id: String,
  minZoom: Float = 0.0f,
  maxZoom: Float = 24.0f,
  visible: Boolean = true,
  opacity: Expression<FloatValue> = const(1f),
  opacityTransition: TransitionOptions? = null,
  color: Expression<ColorValue> = const(Color.Black),
  colorTransition: TransitionOptions? = null,
  pattern: Expression<ImageValue> = nil(),
  patternTransition: TransitionOptions? = null,
) {
  val compile = rememberPropertyCompiler()

  val compiledOpacity = compile(opacity)
  val compiledColor = compile(color)
  val compiledPattern = compile(pattern)

  LayerNode(
    factory = { BackgroundLayer(id = id) },
    update = {
      set(minZoom) { layer.minZoom = it }
      set(maxZoom) { layer.maxZoom = it }
      set(visible) { layer.visible = it }
      set(compiledColor) { layer.setBackgroundColor(it) }
      set(colorTransition) { layer.setBackgroundColorTransition(it) }
      set(compiledPattern) { layer.setBackgroundPattern(it) }
      set(patternTransition) { layer.setBackgroundPatternTransition(it) }
      set(compiledOpacity) { layer.setBackgroundOpacity(it) }
      set(opacityTransition) { layer.setBackgroundOpacityTransition(it) }
    },
    onClick = null,
    onLongClick = null,
  )
}

internal class BackgroundLayer(id: String) : Layer(id) {

  override val type: String = "background"

  fun setBackgroundColor(color: CompiledExpression<ColorValue>) {
    setPaintProperty("background-color", color)
  }

  fun setBackgroundColorTransition(options: TransitionOptions?) {
    setPaintTransition("background-color", options)
  }

  fun setBackgroundPattern(pattern: CompiledExpression<ImageValue>) {
    setPaintProperty("background-pattern", pattern)
  }

  fun setBackgroundPatternTransition(options: TransitionOptions?) {
    setPaintTransition("background-pattern", options)
  }

  fun setBackgroundOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("background-opacity", opacity)
  }

  fun setBackgroundOpacityTransition(options: TransitionOptions?) {
    setPaintTransition("background-opacity", options)
  }
}
