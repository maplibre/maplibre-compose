package org.maplibre.compose.layers

import androidx.compose.ui.graphics.GraphicsContext
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
import org.maplibre.compose.util.toStyleJson

internal class LayerPropertyCompiler(
  private val density: Density,
  private val layoutDirection: LayoutDirection,
  private val fontScale: Expression<FloatValue>,
  private val graphicsContext: () -> GraphicsContext,
) {
  /**
   * Compiles [expression]. A null [expression] compiles to a null literal, which leaves the
   * property unset.
   */
  fun <T : ExpressionValue?> compile(
    expression: Expression<T>?,
    units: LayerExpressionContext,
  ): LayerProperty<T> {
    val expression = expression ?: NullLiteral.cast()
    val images =
      layerPropertyImages(
        listOfNotNull(expression, units.emScale, units.spScale, fontScale),
        density,
        layoutDirection,
        graphicsContext,
      )
    return if (images.requests.isEmpty()) {
      val value = expression.compile(context(images, emptyMap(), units)).toStyleJson()
      LayerProperty { value }
    } else {
      LayerProperty(images.requests) { resolved ->
        expression.compile(context(images, resolved, units)).toStyleJson()
      }
    }
  }

  private fun context(
    images: LayerPropertyImages,
    resolved: Map<StyleImageRequest, String>,
    units: LayerExpressionContext,
  ) =
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
        get() = units.emScale ?: unscaledUnit(TextUnitType.Em)

      override val spScale: Expression<FloatValue>
        get() = units.spScale ?: unscaledUnit(TextUnitType.Sp)

      // Use the same linear font scale as SymbolLayer's rendered text size.
      override val dpScale: Expression<FloatValue>
        get() =
          UnitConversion(
            units.spScale ?: error("DP text offsets require a text-unit compiler"),
            fontScale,
            divide = true,
          )

      override fun resolveBitmap(bitmap: BitmapLiteral): String =
        resolved.getValue(images.bitmaps.getValue(bitmap))

      override fun resolvePainter(painter: PainterLiteral): String =
        resolved.getValue(images.painters.getValue(painter))
    }
}
