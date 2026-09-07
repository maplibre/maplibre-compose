package org.maplibre.compose.expr.compiler

internal object ExprDiagnostics {
  const val PREFIX: String = "expr { }: "

  fun notConstable(typeName: String): String =
    PREFIX +
      "Cannot capture $typeName as an expression literal. Constable types are Boolean, " +
      "Number, String, Color, Dp, Offset, DpOffset, DpPadding, TextUnit, Duration, " +
      "ProjectionTransition, EnumValue, ImageBitmap, Painter, GeoJSON, lists of those, " +
      "or Expression."

  fun mapArgsToKotlin(callee: String): String =
    PREFIX +
      "Cannot pass map-evaluated values to $callee. The map cannot run Kotlin per feature. " +
      "Use an expr helper or an operator the plugin lowers."

  fun varNotAllowed(): String =
    PREFIX + "`var` is not allowed. MapLibre expressions are pure; use val."

  fun loopNotAllowed(): String = PREFIX + "Loops are not allowed in expr { }."

  fun tryNotAllowed(): String = PREFIX + "try/catch is not allowed in expr { }."

  fun assignmentNotAllowed(): String = PREFIX + "Assignment is not allowed in expr { }."

  fun nestedLambda(): String =
    PREFIX + "Nested lambdas are only supported as the body of bind(name, value) { ... }."

  fun emptyBlock(): String = PREFIX + "Block is empty."

  fun unsupportedStatement(kind: String): String = PREFIX + "Unsupported statement: $kind."

  fun receiverIsNotAValue(): String =
    PREFIX + "The expr receiver is not a value; use feature, zoom, or a helper."

  fun composeOnlyConstructor(name: String): String =
    PREFIX +
      "Cannot construct $name from map-evaluated numbers. MapLibre has no array constructor " +
      "for dynamic components; offset, padding, and listOf need composition-time values."

  fun stopInputMustBeConst(): String =
    PREFIX + "interpolate/step stop inputs must be composition-time numbers."

  fun imageOptionsMustBeConst(): String =
    PREFIX + "image(bitmap/painter) options must be composition-time values."

  fun textUnitOffsetMustBeConst(): String =
    PREFIX +
      "Text-unit offsets must be composition-time TextUnit values. MapLibre cannot build an " +
      "em/sp pair from two map-evaluated numbers."

  fun unsupportedHelper(name: String): String = PREFIX + "Unsupported expr helper $name."
}
