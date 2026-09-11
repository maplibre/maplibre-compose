package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnitType
import org.maplibre.compose.expressions.ast.BitmapLiteral
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.FontLiteral
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.expressions.ast.PainterLiteral
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.div
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.StyleNode

internal class LayerPropertyCompiler(
  private val styleNode: StyleNode,
  private val density: Density,
  private val layoutDirection: LayoutDirection,
  private val emScale: Expression<FloatValue>? = null,
  private val spScale: Expression<FloatValue>? = null,
) {
  /**
   * Compiles [expression]. A null [expression] compiles to a null literal, which leaves the
   * property unset.
   */
  @Composable
  operator fun <T : ExpressionValue?> invoke(expression: Expression<T>?): CompiledExpression<T> {
    val expression = expression ?: NullLiteral.cast()
    val resources =
      rememberLayerPropertyResources(expression, styleNode, density, layoutDirection)
        ?: return NullLiteral.cast()
    return remember(this, expression, resources) { expression.compile(context(resources)) }
  }

  private fun context(resources: LayerPropertyResources) =
    object : ExpressionContext {
      private var seenTextUnitType: TextUnitType? = null

      override val emScale: Expression<FloatValue>
        get() {
          return this@LayerPropertyCompiler.emScale
            ?: when (seenTextUnitType) {
              null -> {
                seenTextUnitType = TextUnitType.Em
                const(1f)
              }

              TextUnitType.Em -> const(1f)
              else -> error("mixing EM and SP units is not supported in most expressions")
            }
        }

      override val spScale: Expression<FloatValue>
        get() {
          return this@LayerPropertyCompiler.spScale
            ?: when (seenTextUnitType) {
              null -> {
                seenTextUnitType = TextUnitType.Sp
                const(1f)
              }

              TextUnitType.Sp -> const(1f)
              else -> error("mixing SP and EM units is not supported in most expressions")
            }
        }

      // Use the same linear font scale as SymbolLayer's rendered text size.
      override val dpScale: Expression<FloatValue>
        get() =
          (this@LayerPropertyCompiler.spScale
            ?: error("DP text offsets require a text-unit compiler")) / const(density.fontScale)

      override fun resolveBitmap(bitmap: BitmapLiteral): String = resources.resolve(bitmap)

      override fun resolvePainter(painter: PainterLiteral): String = resources.resolve(painter)

      override fun resolveFont(font: FontLiteral): List<String> = resources.resolve(font)
    }
}

@Composable
internal fun rememberPropertyCompiler(
  emScale: Expression<FloatValue>? = null,
  spScale: Expression<FloatValue>? = null,
): LayerPropertyCompiler {
  val styleNode = LocalStyleNode.current
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  return remember(styleNode, density, layoutDirection, emScale, spScale) {
    LayerPropertyCompiler(styleNode, density, layoutDirection, emScale, spScale)
  }
}
