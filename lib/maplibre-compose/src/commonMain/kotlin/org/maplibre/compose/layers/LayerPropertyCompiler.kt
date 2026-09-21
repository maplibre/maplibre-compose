package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnitType
import org.maplibre.compose.expressions.ast.BitmapLiteral
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.expressions.ast.PainterLiteral
import org.maplibre.compose.expressions.ast.UnitConversion
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.style.StyleImageRequest
import org.maplibre.compose.style.styleFontScale
import org.maplibre.compose.util.toStyleJson

internal class LayerPropertyCompiler(
  private val density: Density,
  private val layoutDirection: LayoutDirection,
  private val fontScale: Expression<FloatValue>,
  private val emScale: Expression<FloatValue>? = null,
  private val spScale: Expression<FloatValue>? = null,
) {
  /**
   * Compiles [expression]. A null [expression] compiles to a null literal, which leaves the
   * property unset.
   */
  @Composable
  operator fun <T : ExpressionValue?> invoke(expression: Expression<T>?): LayerProperty<T> {
    val expression = expression ?: NullLiteral.cast()
    val images =
      rememberLayerPropertyImages(
        listOfNotNull(expression, emScale, spScale, fontScale),
        density,
        layoutDirection,
      )
    return remember(this, expression, images) {
      if (images.requests.isEmpty()) {
        val value = expression.compile(context(images, emptyMap())).toStyleJson()
        LayerProperty { value }
      } else {
        LayerProperty(images.requests) { resolved ->
          expression.compile(context(images, resolved)).toStyleJson()
        }
      }
    }
  }

  private fun context(images: LayerPropertyImages, resolved: Map<StyleImageRequest, String>) =
    object : ExpressionContext {
      private var seenTextUnitType: TextUnitType? = null

      private fun unscaledUnit(type: TextUnitType): Expression<FloatValue> {
        check(seenTextUnitType == null || seenTextUnitType == type) {
          "mixing EM and SP units is not supported in most expressions"
        }
        seenTextUnitType = type
        return const(1f)
      }

      override val emScale: Expression<FloatValue>
        get() = this@LayerPropertyCompiler.emScale ?: unscaledUnit(TextUnitType.Em)

      override val spScale: Expression<FloatValue>
        get() = this@LayerPropertyCompiler.spScale ?: unscaledUnit(TextUnitType.Sp)

      // Use the same linear font scale as SymbolLayer's rendered text size.
      override val dpScale: Expression<FloatValue>
        get() =
          UnitConversion(
            this@LayerPropertyCompiler.spScale
              ?: error("DP text offsets require a text-unit compiler"),
            fontScale,
            divide = true,
          )

      override fun resolveBitmap(bitmap: BitmapLiteral): String =
        resolved.getValue(images.bitmaps.getValue(bitmap))

      override fun resolvePainter(painter: PainterLiteral): String =
        resolved.getValue(images.painters.getValue(painter))
    }
}

@Composable
internal fun rememberPropertyCompiler(
  emScale: Expression<FloatValue>? = null,
  spScale: Expression<FloatValue>? = null,
): LayerPropertyCompiler {
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  val fontScale = styleFontScale()
  return remember(density, layoutDirection, fontScale, emScale, spScale) {
    LayerPropertyCompiler(density, layoutDirection, fontScale, emScale, spScale)
  }
}
