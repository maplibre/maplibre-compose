package org.maplibre.compose.expressions.internal

import org.maplibre.compose.expressions.ExpressionImageReference
import org.maplibre.compose.expressions.ExpressionJsonOptions
import org.maplibre.compose.expressions.ast.BitmapLiteral
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.PainterLiteral
import org.maplibre.compose.expressions.value.FloatValue

internal class ExportExpressionContext(private val options: ExpressionJsonOptions) :
  ExpressionContext {
  private val bitmaps = mutableMapOf<BitmapLiteral, String>()
  private val painters = mutableMapOf<PainterLiteral, String>()

  override val emScale: Expression<FloatValue>
    get() = requireNotNull(options.emScale) { "Exporting EM text requires emScale" }

  override val spScale: Expression<FloatValue>
    get() = requireNotNull(options.spScale) { "Exporting SP text requires spScale" }

  override val dpScale: Expression<FloatValue>
    get() = requireNotNull(options.dpScale) { "Exporting DP text offsets requires dpScale" }

  override fun resolveBitmap(bitmap: BitmapLiteral): String =
    bitmaps.getOrPut(bitmap) {
      resolve(ExpressionImageReference.Bitmap(bitmap.value, bitmap.sdf, bitmap.stretch))
    }

  override fun resolvePainter(painter: PainterLiteral): String =
    painters.getOrPut(painter) {
      resolve(
        ExpressionImageReference.Painter(
          painter.value,
          painter.size,
          painter.sdf,
          painter.stretch,
          painter.alpha,
          painter.colorFilter,
        )
      )
    }

  private fun resolve(reference: ExpressionImageReference): String {
    val resolver =
      requireNotNull(options.imageResolver) {
        "Exporting images requires imageResolver: $reference"
      }
    return requireNotNull(resolver.resolve(reference)) {
      "Image resolver could not resolve: $reference"
    }
  }
}
