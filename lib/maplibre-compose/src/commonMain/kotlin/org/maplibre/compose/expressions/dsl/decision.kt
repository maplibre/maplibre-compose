package org.maplibre.compose.expressions.dsl

import kotlin.jvm.JvmName
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.FunctionCall
import org.maplibre.compose.expressions.value.AnyValue
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.CollatorValue
import org.maplibre.compose.expressions.value.ComparableValue
import org.maplibre.compose.expressions.value.EnumValue
import org.maplibre.compose.expressions.value.EquatableValue
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.MatchableValue
import org.maplibre.compose.expressions.value.StringValue

/**
 * Selects the first output from the given [conditions] whose corresponding test condition evaluates
 * to `true`, or the [fallback] value otherwise.
 *
 * Example:
 * ```kt
 * switch(
 *   condition(
 *     test = feature.has("color1") and feature.has("color2"),
 *     output = interpolate(
 *       linear(),
 *       zoom(),
 *       1 to feature["color1"].convertToColor(),
 *       20 to feature["color2"].convertToColor()
 *     ),
 *   ),
 *   condition(
 *     test = feature.has("color"),
 *     output = feature["color"].convertToColor(),
 *   ),
 *   fallback = const(Color.Red),
 * )
 * ```
 *
 * If the feature has both a "color1" and "color2" property, the result is an interpolation between
 * these two colors based on the zoom level. Otherwise, if the feature has a "color" property, that
 * color is returned. If the feature has none of the three, the color red is returned.
 */
public fun <T : ExpressionValue?> switch(
  conditions: List<Condition<T>>,
  fallback: Expression<T>,
): Expression<T> =
  when (conditions.size) {
    0 -> fallback
    else -> {
      val args =
        buildList(conditions.size * 2 + 1) {
          for ((test, output) in conditions) {
            add(test)
            add(output)
          }
          add(fallback)
        }
      FunctionCall.of("case", args).cast()
    }
  }

/**
 * Selects the first output from the given [conditions] whose corresponding test condition evaluates
 * to `true`, or the [fallback] value otherwise.
 *
 * Example:
 * ```kt
 * switch(
 *   condition(
 *     test = feature.has("color1") and feature.has("color2"),
 *     output = interpolate(
 *       linear(),
 *       zoom(),
 *       1 to feature["color1"].convertToColor(),
 *       20 to feature["color2"].convertToColor()
 *     ),
 *   ),
 *   condition(
 *     test = feature.has("color"),
 *     output = feature["color"].convertToColor(),
 *   ),
 *   fallback = const(Color.Red),
 * )
 * ```
 *
 * If the feature has both a "color1" and "color2" property, the result is an interpolation between
 * these two colors based on the zoom level. Otherwise, if the feature has a "color" property, that
 * color is returned. If the feature has none of the three, the color red is returned.
 */
public fun <T : ExpressionValue?> switch(
  vararg conditions: Condition<T>,
  fallback: Expression<T>,
): Expression<T> = switch(conditions.asList(), fallback)

/** See [case] */
public data class Condition<out T : ExpressionValue?>
internal constructor(
  internal val test: Expression<BooleanValue>,
  internal val output: Expression<T>,
)

/** Create a [Condition], see [case] */
public fun <T : ExpressionValue?> condition(
  test: Expression<BooleanValue>,
  output: Expression<T>,
): Condition<T> = Condition(test, output)

/**
 * Selects the output from the given [cases] whose label value matches the [input], or the
 * [fallback] value if no match is found.
 *
 * Each label must be unique. If the input type does not match the type of the labels, the result
 * will be the [fallback] value.
 *
 * Example:
 * ```kt
 * switch(
 *   input = feature["building_type"],
 *   case(
 *     label = "residential",
 *     output = const(Color.Cyan),
 *   ),
 *   case(
 *     label = listOf("commercial", "industrial"),
 *     output = const(Color.Yellow),
 *   ),
 *   fallback = const(Color.Red),
 * )
 * ```
 *
 * If the feature has a property "building_type" with the value "residential", cyan is returned.
 * Otherwise, if the value of that property is either "commercial" or "industrial", yellow is
 * returned. If none of that is true, the fallback is returned, i.e. red.
 */
public fun <I : MatchableValue, O : ExpressionValue?> switch(
  input: Expression<I?>,
  cases: List<Case<I, O>>,
  fallback: Expression<O>,
): Expression<O> = match(input, cases, fallback)

/**
 * Selects the output from the given [cases] whose label value matches the [input], or the
 * [fallback] value if no match is found.
 *
 * The [input] has a type known only when the map evaluates the expression, such as a feature
 * property, so the labels may be of any one matchable type. If the input type does not match the
 * type of the labels, the result will be the [fallback] value. See [AnyValue].
 */
@JvmName("switchAny")
public fun <I : MatchableValue, O : ExpressionValue?> switch(
  input: Expression<AnyValue?>,
  cases: List<Case<I, O>>,
  fallback: Expression<O>,
): Expression<O> = match(input, cases, fallback)

/**
 * Selects the output from the given [cases] whose label value matches the [input], or the
 * [fallback] value if no match is found.
 *
 * The [input] has a type known only when the map evaluates the expression, such as a feature
 * property, so the labels may be of any one matchable type. If the input type does not match the
 * type of the labels, the result will be the [fallback] value. See [AnyValue].
 */
@JvmName("switchAny")
public fun <I : MatchableValue, O : ExpressionValue?> switch(
  input: Expression<AnyValue?>,
  vararg cases: Case<I, O>,
  fallback: Expression<O>,
): Expression<O> = match(input, cases.asList(), fallback)

private fun <O : ExpressionValue?> match(
  input: Expression<*>,
  cases: List<Case<*, O>>,
  fallback: Expression<O>,
): Expression<O> =
  when (cases.size) {
    0 -> fallback
    else -> {
      val args =
        buildList(cases.size * 2 + 2) {
          add(input)
          for ((label, output) in cases) {
            add(label)
            add(output)
          }
          add(fallback)
        }
      val caseCount = cases.size
      FunctionCall.of(
          "match",
          args,
          isLiteralArg = { i ->
            // label positions are odd, starting from 1 and not including the fallback
            i in 1..(caseCount * 2) && i % 2 == 1
          },
        )
        .cast()
    }
  }

/**
 * Selects the output from the given [cases] whose label value matches the [input], or the
 * [fallback] value if no match is found.
 *
 * Each label must be unique. If the input type does not match the type of the labels, the result
 * will be the [fallback] value.
 *
 * Example:
 * ```kt
 * switch(
 *   input = feature["building_type"],
 *   case(
 *     label = "residential",
 *     output = const(Color.Cyan),
 *   ),
 *   case(
 *     label = listOf("commercial", "industrial"),
 *     output = const(Color.Yellow),
 *   ),
 *   fallback = const(Color.Red),
 * )
 * ```
 *
 * If the feature has a property "building_type" with the value "residential", cyan is returned.
 * Otherwise, if the value of that property is either "commercial" or "industrial", yellow is
 * returned. If none of that is true, the fallback is returned, i.e. red.
 */
public fun <I : MatchableValue, O : ExpressionValue?> switch(
  input: Expression<I?>,
  vararg cases: Case<I, O>,
  fallback: Expression<O>,
): Expression<O> = switch(input, cases.asList(), fallback)

/** See [switch] */
public data class Case<@Suppress("unused") in I : MatchableValue, out O : ExpressionValue?>
internal constructor(internal val label: Expression<*>, internal val output: Expression<O>)

/** Create a [Case], see [switch] */
public fun <O : ExpressionValue?> case(label: String, output: Expression<O>): Case<StringValue, O> =
  Case(const(label), output)

/** Create a [Case], see [switch] */
public fun <O : ExpressionValue, E : EnumValue<E>> case(
  label: E,
  output: Expression<O>,
): Case<E, O> = Case(const(label), output)

/** Create a [Case], see [switch] */
public fun <O : ExpressionValue?> case(label: Number, output: Expression<O>): Case<FloatValue, O> =
  Case(const(label.toFloat()), output)

/** Create a [Case], see [switch] */
@JvmName("stringsCase")
public fun <O : ExpressionValue?> case(
  label: List<String>,
  output: Expression<O>,
): Case<StringValue, O> = Case(const(label), output)

/** Create a [Case], see [switch] */
@JvmName("enumsCase")
public fun <O : ExpressionValue, E : EnumValue<E>> case(
  label: List<E>,
  output: Expression<O>,
): Case<E, O> = Case(const(label), output)

/** Create a [Case], see [switch] */
@JvmName("numbersCase")
public fun <O : ExpressionValue?> case(
  label: List<Number>,
  output: Expression<O>,
): Case<FloatValue, O> = Case(const(label), output)

/**
 * Evaluates each expression in [values] in turn until the first non-null value is obtained, and
 * returns that value. The result is null when every value is null.
 *
 * An assertion such as [asString] inside [values] aborts on a null input instead of moving on to
 * the next value. Pass the untyped source, or
 * [cast][org.maplibre.compose.expressions.ast.Expression.cast] it to a nullable type, and assert or
 * convert the result.
 */
public fun <T : ExpressionValue> coalesce(vararg values: Expression<T?>): Expression<T?> =
  FunctionCall.of("coalesce", values.asList()).cast()

/**
 * Evaluates each expression in [values] in turn until the first non-null value is obtained, and
 * returns that value. Returns [fallback] when every value is null, so the result is not null when
 * [fallback] is not.
 *
 * An assertion such as [asString] inside [values] aborts on a null input instead of moving on to
 * the next value. Pass the untyped source, or
 * [cast][org.maplibre.compose.expressions.ast.Expression.cast] it to a nullable type, and assert or
 * convert the result.
 */
public fun <T : ExpressionValue> coalesce(
  vararg values: Expression<T?>,
  fallback: Expression<T>,
): Expression<T> = FunctionCall.of("coalesce", values.asList() + fallback).cast()

/**
 * Returns whether this expression is equal to [other].
 *
 * Values of unknown type, such as feature properties, are compared when the map evaluates the
 * expression. A value whose type differs from [other] is not equal.
 */
public infix fun Expression<EquatableValue?>.eq(
  other: Expression<EquatableValue?>
): Expression<BooleanValue> = FunctionCall.of("==", this, other).cast()

/**
 * Returns whether the [left] string expression is equal to the [right] string expression. An
 * optional [collator] (see [collator] function) can be specified to control locale-dependent string
 * comparisons.
 */
public fun eq(
  left: Expression<StringValue>,
  right: Expression<StringValue>,
  collator: Expression<CollatorValue>,
): Expression<BooleanValue> = FunctionCall.of("==", left, right, collator).cast()

/**
 * Returns whether this expression is not equal to [other].
 *
 * Values of unknown type, such as feature properties, are compared when the map evaluates the
 * expression. A value whose type differs from [other] is not equal.
 */
public infix fun Expression<EquatableValue?>.neq(
  other: Expression<EquatableValue?>
): Expression<BooleanValue> = FunctionCall.of("!=", this, other).cast()

/**
 * Returns whether the [left] string expression is not equal to the [right] string expression. An
 * optional [collator] (see [collator]) can be specified to control locale-dependent string
 * comparisons.
 */
public fun neq(
  left: Expression<StringValue>,
  right: Expression<StringValue>,
  collator: Expression<CollatorValue>,
): Expression<BooleanValue> = FunctionCall.of("!=", left, right, collator).cast()

/**
 * Returns whether this expression is strictly greater than [other].
 *
 * Strings are compared lexicographically (`"b" > "a"`).
 */
public infix fun <T> Expression<ComparableValue<T>>.gt(
  other: Expression<ComparableValue<T>>
): Expression<BooleanValue> = FunctionCall.of(">", this, other).cast()

/**
 * Returns whether the [left] string expression is strictly greater than the [right] string
 * expression. An optional [collator] (see [collator]) can be specified to control locale-dependent
 * string comparisons.
 *
 * Strings are compared lexicographically (`"b" > "a"`).
 */
public fun gt(
  left: Expression<StringValue>,
  right: Expression<StringValue>,
  collator: Expression<CollatorValue>,
): Expression<BooleanValue> = FunctionCall.of(">", left, right, collator).cast()

/**
 * Returns whether this expression is strictly less than [other].
 *
 * Strings are compared lexicographically (`"a" < "b"`).
 */
public infix fun <T> Expression<ComparableValue<T>>.lt(
  other: Expression<ComparableValue<T>>
): Expression<BooleanValue> = FunctionCall.of("<", this, other).cast()

/**
 * Returns whether the [left] string expression is strictly less than the [right] string expression.
 * An optional [collator] (see [collator]) can be specified to control locale-dependent string
 * comparisons.
 *
 * Strings are compared lexicographically (`"a" < "b"`).
 */
public fun lt(
  left: Expression<StringValue>,
  right: Expression<StringValue>,
  collator: Expression<CollatorValue>,
): Expression<BooleanValue> = FunctionCall.of("<", left, right, collator).cast()

/**
 * Returns whether this expression is greater than or equal to [other].
 *
 * Strings are compared lexicographically (`"b" ≥ "a"`).
 */
public infix fun <T> Expression<ComparableValue<T>>.gte(
  other: Expression<ComparableValue<T>>
): Expression<BooleanValue> = FunctionCall.of(">=", this, other).cast()

/**
 * Returns whether the [left] string expression is greater than or equal to the [right] string
 * expression. An optional [collator] (see [collator]) can be specified to control locale-dependent
 * string comparisons.
 *
 * Strings are compared lexicographically (`"b" ≥ "a"`).
 */
public fun gte(
  left: Expression<StringValue>,
  right: Expression<StringValue>,
  collator: Expression<CollatorValue>,
): Expression<BooleanValue> = FunctionCall.of(">=", left, right, collator).cast()

/**
 * Returns whether this string expression is less than or equal to [other].
 *
 * Strings are compared lexicographically (`"a" ≤ "b"`).
 */
public infix fun <T> Expression<ComparableValue<T>>.lte(
  other: Expression<ComparableValue<T>>
): Expression<BooleanValue> = FunctionCall.of("<=", this, other).cast()

/**
 * Returns whether the [left] string expression is less than or equal to the [right] string
 * expression. An optional [collator] (see [collator]) can be specified to control locale-dependent
 * string comparisons.
 *
 * Strings are compared lexicographically (`"a" < "b"`).
 */
public fun lte(
  left: Expression<StringValue>,
  right: Expression<StringValue>,
  collator: Expression<CollatorValue>,
): Expression<BooleanValue> = FunctionCall.of("<=", left, right, collator).cast()

/** Returns whether all [expressions] are `true`. */
public fun all(vararg expressions: Expression<BooleanValue>): Expression<BooleanValue> =
  FunctionCall.of("all", expressions.asList()).cast()

/** Returns whether both this and [other] expressions are `true`. */
public infix fun Expression<BooleanValue>.and(
  other: Expression<BooleanValue>
): Expression<BooleanValue> = all(this, other)

/** Returns whether any [expressions] are `true`. */
public fun any(vararg expressions: Expression<BooleanValue>): Expression<BooleanValue> =
  FunctionCall.of("any", expressions.asList()).cast()

/** Returns whether any of this or the [other] expressions are `true`. */
public infix fun Expression<BooleanValue>.or(
  other: Expression<BooleanValue>
): Expression<BooleanValue> = any(this, other)

/** Negates this expression. */
@JvmName("notOperator")
public operator fun Expression<BooleanValue>.not(): Expression<BooleanValue> =
  FunctionCall.of("!", this).cast()
