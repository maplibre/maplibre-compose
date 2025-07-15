package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.StringLiteral
import org.maplibre.compose.expressions.dsl.type

/** The type of value resolved from an expression, as returned by [type]. */
public enum class ExpressionType(override val literal: StringLiteral) : EnumValue<ExpressionType> {
  Number(StringLiteral.of("number")),
  String(StringLiteral.of("string")),
  Object(StringLiteral.of("object")),
  Boolean(StringLiteral.of("boolean")),
  Color(StringLiteral.of("color")),
  Array(StringLiteral.of("array")),
}
