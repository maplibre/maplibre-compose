package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnitType
import kotlinx.coroutines.awaitCancellation
import org.maplibre.compose.expressions.ast.BitmapLiteral
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.expressions.ast.PainterLiteral
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.div
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.style.ImageManager
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.StyleNode

internal class LayerPropertyCompiler(
  private val styleNode: StyleNode,
  private val density: Density,
  private val layoutDirection: LayoutDirection,
  private val emScale: Expression<FloatValue>? = null,
  private val spScale: Expression<FloatValue>? = null,
) {
  private fun context(
    painters: Map<ImageManager.PainterKey, String> = emptyMap(),
    acquiredBitmaps: MutableList<ImageManager.BitmapKey>? = null,
  ) =
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

      override fun resolveBitmap(bitmap: BitmapLiteral): String {
        val key = bitmap.key()
        return styleNode.imageManager.acquireBitmap(key).also { acquiredBitmaps?.add(key) }
      }

      override fun resolvePainter(painter: PainterLiteral): String {
        return painters.getValue(painter.key(density, layoutDirection))
      }
    }

  /**
   * Compiles [expression]. A null [expression] compiles to a null literal, which leaves the
   * property unset.
   */
  @Composable
  operator fun <T : ExpressionValue?> invoke(expression: Expression<T>?): CompiledExpression<T> {
    val expression = expression ?: NullLiteral.cast()
    val painters =
      remember(this, expression) {
        buildSet {
          expression.visit { if (it is PainterLiteral) add(it.key(density, layoutDirection)) }
        }
      }
    if (painters.isNotEmpty()) {
      val graphicsContext = LocalGraphicsContext.current
      return key(this, expression, graphicsContext) {
        produceState<CompiledExpression<T>>(NullLiteral.cast()) {
            val acquired = mutableMapOf<ImageManager.PainterKey, String>()
            val acquiredBitmaps = mutableListOf<ImageManager.BitmapKey>()
            try {
              for (painter in painters) {
                acquired[painter] = styleNode.imageManager.acquirePainter(painter, graphicsContext)
              }
              value = expression.compile(context(acquired, acquiredBitmaps))
              awaitCancellation()
            } finally {
              acquiredBitmaps.forEach(styleNode.imageManager::releaseBitmap)
              acquired.keys.forEach(styleNode.imageManager::releasePainter)
            }
          }
          .value
      }
    }
    DisposableEffect(this, expression) { onDispose { releaseBitmaps(expression) } }
    return remember(this, expression) { expression.compile(context()) }
  }

  private fun releaseBitmaps(expression: Expression<*>) {
    expression.visit { if (it is BitmapLiteral) styleNode.imageManager.releaseBitmap(it.key()) }
  }

  private fun BitmapLiteral.key() = ImageManager.BitmapKey(value, sdf, stretch)

  private fun PainterLiteral.key(
    density: Density,
    layoutDirection: LayoutDirection,
  ): ImageManager.PainterKey =
    ImageManager.PainterKey(
      value,
      density,
      layoutDirection,
      size,
      sdf,
      stretch,
      alpha,
      colorFilter,
    )
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
