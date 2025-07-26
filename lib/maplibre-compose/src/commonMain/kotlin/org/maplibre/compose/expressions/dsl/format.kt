package org.maplibre.compose.expressions.dsl

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.FunctionCall
import org.maplibre.compose.expressions.ast.Options
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FormattableValue
import org.maplibre.compose.expressions.value.FormattedValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.value.TextUnitValue

/**
 * Returns a formatted string for displaying mixed-format text in the `textField` property (see
 * [SymbolLayer][org.maplibre.compose.layers.SymbolLayer]). The input may contain a string literal
 * or expression, including an [image] expression.
 *
 * Example:
 * ```kt
 * format(
 *   span(
 *     feature["name"].asString().substring(0, 1).uppercase(),
 *     textScale = const(1.5f),
 *   ),
 *   span(feature["name"].asString().substring(1))
 * )
 * ```
 *
 * Capitalizes the first letter of the features' property "name" and formats it to be extra-large,
 * the rest of the name is written normally.
 */
public fun format(vararg spans: FormatSpan): Expression<FormattedValue> =
  FunctionCall.of(
      "format",
      *spans.foldToArgs { span ->
        add(span.value)
        add(span.options)
      },
    )
    .cast()

/** Configures a span of text in a [format] expression. */
public fun span(
  value: Expression<StringValue>,
  textFont: Expression<StringValue>? = null,
  textColor: Expression<ColorValue>? = null,
  textSize: Expression<TextUnitValue>? = null,
): FormatSpan =
  FormatSpan(value = value, textFont = textFont, textColor = textColor, textSize = textSize)

/** Configures a span of text in a [format] expression. */
public fun span(
  value: String,
  textFont: String? = null,
  textColor: Color? = null,
  textSize: TextUnit? = null,
): FormatSpan =
  span(
    value = const(value),
    textFont = textFont?.let { const(it) },
    textColor = textColor?.let { const(it) },
    textSize = textSize?.let { const(it) },
  )

/** Configures an image in a [format] expression. */
public fun span(value: Expression<FormattableValue>): FormatSpan = FormatSpan(value = value)

/** Represents a component of a [format] expression. See [span]. */
public data class FormatSpan
internal constructor(
  val value: Expression<FormattableValue>,
  val textFont: Expression<StringValue>? = null,
  val textColor: Expression<ColorValue>? = null,
  val textSize: Expression<TextUnitValue>? = null,
) {
  internal val options
    get() =
      Options.build(
        fun MutableMap<String, Expression<*>>.() {
          textFont?.let { put("text-font", it) }
          textColor?.let { put("text-color", it) }
          textSize?.let { put("font-scale", it) }
        }
      )
}
