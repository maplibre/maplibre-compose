package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline
import org.maplibre.compose.expressions.dsl.type

/**
 * The type of value resolved from an expression, as returned by [type].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class ExpressionType private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<ExpressionType> {
    public val Number: ExpressionType = ExpressionType("number")
    public val String: ExpressionType = ExpressionType("string")
    public val Object: ExpressionType = ExpressionType("object")
    public val Boolean: ExpressionType = ExpressionType("boolean")
    public val Color: ExpressionType = ExpressionType("color")
    public val Array: ExpressionType = ExpressionType("array")

    public override val entries: List<ExpressionType> =
      listOf(Number, String, Object, Boolean, Color, Array)
  }
}
