package org.maplibre.compose.style.expressions.dsl

import org.maplibre.compose.style.expressions.ast.Expression
import org.maplibre.compose.style.expressions.ast.FunctionCall
import org.maplibre.compose.style.expressions.value.ColorValue
import org.maplibre.compose.style.expressions.value.FloatValue
import org.maplibre.compose.style.expressions.value.IntValue
import org.maplibre.compose.style.expressions.value.VectorValue

/**
 * Returns a four-element list, containing the color's red, green, blue, and alpha components, in
 * that order.
 */
public fun Expression<ColorValue>.toRgbaComponents(): Expression<VectorValue<Number>> =
  FunctionCall.of("to-rgba", this).cast()

/**
 * Creates a color value from [red], [green], and [blue] components, which must range between 0 and
 * 255, and optionally an [alpha] component which must range between 0 and 1.
 *
 * If any component is out of range, the expression is an error.
 */
public fun rgbColor(
  red: Expression<IntValue>,
  green: Expression<IntValue>,
  blue: Expression<IntValue>,
  alpha: Expression<FloatValue>? = null,
): Expression<ColorValue> =
  if (alpha != null) {
      FunctionCall.of("rgba", red, green, blue, alpha)
    } else {
      FunctionCall.of("rgb", red, green, blue)
    }
    .cast()
