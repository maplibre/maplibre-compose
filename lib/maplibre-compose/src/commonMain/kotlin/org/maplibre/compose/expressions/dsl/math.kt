package org.maplibre.compose.expressions.dsl

import kotlin.jvm.JvmName
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.FunctionCall
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.IntValue
import org.maplibre.compose.expressions.value.NumberValue

/** Returns mathematical constant ln(2) = natural logarithm of 2. */
public val LN_2: Expression<FloatValue> = FunctionCall.of("ln2").cast()

/** Returns the mathematical constant π */
public val PI: Expression<FloatValue> = FunctionCall.of("pi").cast()

/** Returns the mathematical constant e */
public val E: Expression<FloatValue> = FunctionCall.of("e").cast()

/** Returns the sum of this number expression with [other]. */
public operator fun <U, V : NumberValue<U>> Expression<V>.plus(
  other: Expression<V>
): Expression<V> = FunctionCall.of("+", this, other).cast()

/** Returns the product of this number expression with [other]. */
@JvmName("timesUnitLeft")
public operator fun <U, V : NumberValue<U>> Expression<V>.times(
  other: Expression<FloatValue>
): Expression<V> = FunctionCall.of("*", this, other).cast()

/** Returns the product of this number expression with [other]. */
@JvmName("timesUnitRight")
public operator fun <U, V : NumberValue<U>> Expression<FloatValue>.times(
  other: Expression<V>
): Expression<V> = FunctionCall.of("*", this, other).cast()

/** Returns the product of this number expression with [other]. */
public operator fun Expression<FloatValue>.times(
  other: Expression<FloatValue>
): Expression<FloatValue> = FunctionCall.of("*", this, other).cast()

/** Returns the result of subtracting [other] from this number expression. */
public operator fun <U, V : NumberValue<U>> Expression<V>.minus(
  other: Expression<NumberValue<U>>
): Expression<V> = FunctionCall.of("-", this, other).cast()

/** Negates this number expression. */
public operator fun <U, V : NumberValue<U>> Expression<V>.unaryMinus(): Expression<V> =
  FunctionCall.of("-", this).cast()

/** Returns the result of floating point division of this number expression by [divisor]. */
@JvmName("divUnitBoth")
public operator fun <U, V : NumberValue<U>> Expression<V>.div(
  divisor: Expression<V>
): Expression<FloatValue> = FunctionCall.of("/", this, divisor).cast()

/** Returns the result of floating point division of this number expression by [divisor]. */
@JvmName("divUnitLeftOnly")
public operator fun <U, V : NumberValue<U>> Expression<V>.div(
  divisor: Expression<FloatValue>
): Expression<V> = FunctionCall.of("/", this, divisor).cast()

/** Returns the result of floating point division of this number expression by [divisor]. */
public operator fun Expression<FloatValue>.div(
  divisor: Expression<FloatValue>
): Expression<FloatValue> = FunctionCall.of("/", this, divisor).cast()

/** Returns the remainder after integer division of this number expression by [divisor]. */
public operator fun <U, V : NumberValue<U>> Expression<V>.rem(
  divisor: Expression<IntValue>
): Expression<V> = FunctionCall.of("%", this, divisor).cast()

/** Returns the result of raising this number expression to the power of [exponent]. */
public fun Expression<FloatValue>.pow(exponent: Expression<FloatValue>): Expression<FloatValue> =
  FunctionCall.of("^", this, exponent).cast()

/** Returns the result of raising this number expression to the power of [exponent]. */
public fun Expression<FloatValue>.pow(exponent: Float): Expression<FloatValue> =
  FunctionCall.of("^", this, const(exponent)).cast()

/** Returns the square root of [value]. */
public fun sqrt(value: Expression<FloatValue>): Expression<FloatValue> =
  FunctionCall.of("sqrt", value).cast()

/** Returns the base-ten logarithm of [value]. */
public fun log10(value: Expression<FloatValue>): Expression<FloatValue> =
  FunctionCall.of("log10", value).cast()

/** Returns the natural logarithm of [value]. */
public fun ln(value: Expression<FloatValue>): Expression<FloatValue> =
  FunctionCall.of("ln", value).cast()

/** Returns the base-two logarithm of [value]. */
public fun log2(value: Expression<FloatValue>): Expression<FloatValue> =
  FunctionCall.of("log2", value).cast()

/** Returns the sine of [value]. */
public fun sin(value: Expression<FloatValue>): Expression<FloatValue> =
  FunctionCall.of("sin", value).cast()

/** Returns the cosine of [value]. */
public fun cos(value: Expression<FloatValue>): Expression<FloatValue> =
  FunctionCall.of("cos", value).cast()

/** Returns the tangent of [value]. */
public fun tan(value: Expression<FloatValue>): Expression<FloatValue> =
  FunctionCall.of("tan", value).cast()

/** Returns the arcsine of [value]. */
public fun asin(value: Expression<FloatValue>): Expression<FloatValue> =
  FunctionCall.of("asin", value).cast()

/** Returns the arccosine of [value]. */
public fun acos(value: Expression<FloatValue>): Expression<FloatValue> =
  FunctionCall.of("acos", value).cast()

/** Returns the arctangent of [value]. */
public fun atan(value: Expression<FloatValue>): Expression<FloatValue> =
  FunctionCall.of("atan", value).cast()

/** Returns the smallest of all given [numbers]. */
public fun <U, V : NumberValue<U>> min(vararg numbers: Expression<V>): Expression<V> =
  FunctionCall.of("min", *numbers).cast()

/** Returns the greatest of all given [numbers]. */
public fun <U, V : NumberValue<U>> max(vararg numbers: Expression<V>): Expression<V> =
  FunctionCall.of("max", *numbers).cast()

/** Returns the absolute value of [value], i.e. always a positive value. */
public fun <U, V : NumberValue<U>> abs(value: Expression<V>): Expression<V> =
  FunctionCall.of("abs", value).cast()

/**
 * Rounds [value] to the nearest integer. Halfway values are rounded away from zero.
 *
 * For example `round(const(-1.5))` evaluates to `-2`.
 */
public fun round(value: Expression<FloatValue>): Expression<IntValue> =
  FunctionCall.of("round", value).cast()

/** Returns the smallest integer that is greater than or equal to [value]. */
public fun ceil(value: Expression<FloatValue>): Expression<IntValue> =
  FunctionCall.of("ceil", value).cast()

/** Returns the largest integer that is less than or equal to [value]. */
public fun floor(value: Expression<FloatValue>): Expression<IntValue> =
  FunctionCall.of("floor", value).cast()
