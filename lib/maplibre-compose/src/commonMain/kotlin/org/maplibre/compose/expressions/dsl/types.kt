package org.maplibre.compose.expressions.dsl

import androidx.compose.ui.unit.TextUnitType
import kotlin.enums.enumEntries
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.FunctionCall
import org.maplibre.compose.expressions.ast.Options
import org.maplibre.compose.expressions.ast.TextUnitCalculation
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.CollatorValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.DpPaddingValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.EnumValue
import org.maplibre.compose.expressions.value.ExpressionType
import org.maplibre.compose.expressions.value.FloatOffsetValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.IntValue
import org.maplibre.compose.expressions.value.ListValue
import org.maplibre.compose.expressions.value.MapValue
import org.maplibre.compose.expressions.value.MillisecondsValue
import org.maplibre.compose.expressions.value.NumberValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.value.TextUnitValue

/** Returns a string describing the type of this expression. */
public fun Expression<*>.type(): Expression<ExpressionType> = FunctionCall.of("typeof", this).cast()

/**
 * Asserts that this is a list (optionally with a specific item [type] and [length]).
 *
 * If, when the input expression is evaluated, it is not of the asserted type, then this assertion
 * will cause the whole expression to be aborted.
 */
public fun Expression<*>.asList(
  type: Expression<ExpressionType> = nil(),
  length: Expression<IntValue> = nil(),
): Expression<ListValue<*>> = FunctionCall.of("array", this, type, length).cast()

/**
 * Asserts that this is a list of numbers, optionally with a specific [length].
 *
 * If, when the input expression is evaluated, it is not of the asserted type, then this assertion
 * will cause the whole expression to be aborted.
 */
public fun <U, V : NumberValue<U>> Expression<*>.asVector(
  length: Expression<IntValue> = nil()
): Expression<V> = asList(const(ExpressionType.Number), length).cast()

/**
 * Asserts that this is a list of numbers of length 2.
 *
 * If, when the input expression is evaluated, it is not of the asserted type, then this assertion
 * will cause the whole expression to be aborted.
 */
public fun Expression<*>.asOffset(): Expression<FloatOffsetValue> =
  asList(const(ExpressionType.Number), const(2)).cast()

/**
 * Asserts that this is a list of numbers of length 2.
 *
 * If, when the input expression is evaluated, it is not of the asserted type, then this assertion
 * will cause the whole expression to be aborted.
 */
public fun Expression<*>.asDpOffset(): Expression<DpOffsetValue> =
  asList(const(ExpressionType.Number), const(2)).cast()

/**
 * Asserts that this is a list of numbers of length 4.
 *
 * If, when the input expression is evaluated, it is not of the asserted type, then this assertion
 * will cause the whole expression to be aborted.
 */
public fun Expression<*>.asPadding(): Expression<DpPaddingValue> =
  asList(const(ExpressionType.Number), const(2)).cast()

/**
 * Asserts that this value is a string.
 *
 * In case this expression is not a string, each of the [fallbacks] is evaluated in order until a
 * string is obtained. If none of the inputs are strings, the expression is an error.
 */
public fun Expression<*>.asString(vararg fallbacks: Expression<*>): Expression<StringValue> =
  FunctionCall.of("string", this, *fallbacks).cast()

/**
 * Asserts that this value is an entry of the enum specified by [T].
 *
 * In case this expression is not an entry of the enum, each of the [fallbacks] is evaluated in
 * order until a match is obtained. If none of the inputs match, the expression is an error.
 */
public inline fun <reified T> Expression<*>.asEnum(vararg fallbacks: Expression<*>): Expression<T>
  where T : Enum<T>, T : EnumValue<T> {
  val entries = const(enumEntries<T>().map { it.name })
  return switch(
      condition(entries.contains(this), this),
      *fallbacks.map { condition(entries.contains(it), it) }.toTypedArray(),
      fallback = nil(), // should always error .asString(), which is what we want as per kdoc
    )
    .asString()
    .cast()
}

/**
 * Asserts that this value is a number.
 *
 * In case this expression is not a number, each of the [fallbacks] is evaluated in order until a
 * number is obtained. If none of the inputs are numbers, the expression is an error.
 */
public fun Expression<*>.asNumber(vararg fallbacks: Expression<*>): Expression<FloatValue> =
  FunctionCall.of("number", this, *fallbacks).cast()

/**
 * Asserts that this value is a boolean.
 *
 * In case this expression is not a boolean, each of the [fallbacks] is evaluated in order until a
 * boolean is obtained. If none of the inputs are booleans, the expression is an error.
 */
public fun Expression<*>.asBoolean(vararg fallbacks: Expression<*>): Expression<BooleanValue> =
  FunctionCall.of("boolean", this, *fallbacks).cast()

/**
 * Asserts that this value is a map.
 *
 * In case this expression is not a map, each of the [fallbacks] is evaluated in order until a map
 * is obtained. If none of the inputs are maps, the expression is an error.
 */
public fun Expression<*>.asMap(vararg fallbacks: Expression<*>): Expression<MapValue<*>> =
  FunctionCall.of("object", this, *fallbacks).cast()

/**
 * Returns a collator for use in locale-dependent comparison operations. The [caseSensitive] and
 * [diacriticSensitive] options default to `false`. The [locale] argument specifies the IETF
 * language tag of the locale to use. If none is provided, the default locale is used. If the
 * requested locale is not available, the collator will use a system-defined fallback locale. Use
 * [resolvedLocale] to test the results of locale fallback behavior.
 */
public fun collator(
  caseSensitive: Expression<BooleanValue>? = null,
  diacriticSensitive: Expression<BooleanValue>? = null,
  locale: Expression<StringValue>? = null,
): Expression<CollatorValue> =
  FunctionCall.of(
      "collator",
      Options.build(
        fun MutableMap<String, Expression<*>>.() {
          caseSensitive?.let { put("case-sensitive", it) }
          diacriticSensitive?.let { put("diacritic-sensitive", it) }
          locale?.let { put("locale", it) }
        }
      ),
    )
    .cast()

/**
 * Returns a collator for use in locale-dependent comparison operations. The [caseSensitive] and
 * [diacriticSensitive] options default to `false`. The [locale] argument specifies the IETF
 * language tag of the locale to use. If none is provided, the default locale is used. If the
 * requested locale is not available, the collator will use a system-defined fallback locale. Use
 * [resolvedLocale] to test the results of locale fallback behavior.
 */
public fun collator(
  caseSensitive: Boolean? = null,
  diacriticSensitive: Boolean? = null,
  locale: String? = null,
): Expression<CollatorValue> =
  collator(
    caseSensitive?.let { const(it) },
    diacriticSensitive?.let { const(it) },
    locale?.let { const(it) },
  )

/**
 * Converts this number into a string representation using the provided formatting rules.
 *
 * @param locale BCP 47 language tag for which locale to use
 * @param currency an ISO 4217 code to use for currency-style formatting
 * @param minFractionDigits minimum fractional digits to include
 * @param maxFractionDigits maximum fractional digits to include
 */
public fun Expression<NumberValue<*>>.formatToString(
  locale: Expression<StringValue>? = null,
  currency: Expression<StringValue>? = null,
  minFractionDigits: Expression<IntValue>? = null,
  maxFractionDigits: Expression<IntValue>? = null,
): Expression<StringValue> =
  FunctionCall.of(
      "number-format",
      this,
      Options.build(
        fun MutableMap<String, Expression<*>>.() {
          locale?.let { put("locale", it) }
          currency?.let { put("currency", it) }
          minFractionDigits?.let { put("min-fraction-digits", it) }
          maxFractionDigits?.let { put("max-fraction-digits", it) }
        }
      ),
    )
    .cast()

/**
 * Converts this number into a string representation using the provided formatting rules.
 *
 * @param locale BCP 47 language tag for which locale to use
 * @param currency an ISO 4217 code to use for currency-style formatting
 * @param minFractionDigits minimum fractional digits to include
 * @param maxFractionDigits maximum fractional digits to include
 */
public fun Expression<NumberValue<*>>.formatToString(
  locale: String? = null,
  currency: String? = null,
  minFractionDigits: Int? = null,
  maxFractionDigits: Int? = null,
): Expression<StringValue> =
  formatToString(
    locale?.let { const(it) },
    currency?.let { const(it) },
    minFractionDigits?.let { const(it) },
    maxFractionDigits?.let { const(it) },
  )

/**
 * Converts this expression to a string.
 *
 * If this is ...
 * - `null`, the result is `""`
 * - a boolean, the result is `"true"` or `"false"`
 * - a number, it is converted to a string as specified by the "NumberToString" algorithm of the
 *   ECMAScript Language Specification.
 * - a color, it is converted to a string of the form `"rgba(r,g,b,a)"`, where `r`, `g`, and `b` are
 *   numerals ranging from 0 to 255, and `a` ranges from 0 to 1.
 *
 * Otherwise, the input is converted to a string in the format specified by the JSON.stringify
 * function of the ECMAScript Language Specification.
 */
public fun Expression<*>.convertToString(): Expression<StringValue> =
  FunctionCall.of("to-string", this).cast()

/**
 * Converts this expression to a number.
 *
 * If this expression is `null` or `false`, the result is `0`. If this is `true`, the result is `1`.
 * If the input is a string, it is converted to a number as specified by the "ToNumber Applied to
 * the String Type" algorithm of the ECMAScript Language Specification.
 *
 * In case this expression cannot be converted to a number, each of the [fallbacks] is evaluated in
 * order until the first successful conversion is obtained. If none of the inputs can be converted,
 * the expression is an error.
 */
public fun Expression<*>.convertToNumber(vararg fallbacks: Expression<*>): Expression<FloatValue> =
  FunctionCall.of("to-number", this, *fallbacks).cast()

/**
 * Converts this expression to a boolean expression.
 *
 * The result is `false` when then this is an empty string, `0`, `false`,`null` or `NaN`; otherwise
 * it is `true`.
 */
public fun Expression<*>.convertToBoolean(): Expression<BooleanValue> =
  FunctionCall.of("to-boolean", this).cast()

/**
 * Converts this expression to a color expression.
 *
 * In case this expression cannot be converted to a color, each of the [fallbacks] is evaluated in
 * order until the first successful conversion is obtained. If none of the inputs can be converted,
 * the expression is an error.
 */
public fun Expression<*>.convertToColor(vararg fallbacks: Expression<*>): Expression<ColorValue> =
  FunctionCall.of("to-color", this, *fallbacks).cast()

/** Converts a numeric [Expression] to a [DpValue] expression. */
public val Expression<FloatValue>.dp: Expression<DpValue>
  get() = this.cast()

/** Converts a numeric [Expression] in milliseconds to a [MillisecondsValue] expression. */
public val Expression<FloatValue>.milliseconds: Expression<MillisecondsValue>
  get() = this.cast()

/** Converts a numeric [Expression] in seconds to a [MillisecondsValue] expression. */
public val Expression<FloatValue>.seconds: Expression<MillisecondsValue>
  get() = (this * const(1000f)).cast()

/** Converts a numeric [Expression] to an [TextUnitValue] expression in SP. */
public val Expression<FloatValue>.sp: Expression<TextUnitValue>
  get() = TextUnitCalculation.of(this, TextUnitType.Sp)

/** Converts a numeric [Expression] to an [TextUnitValue] expression in EM */
public val Expression<FloatValue>.em: Expression<TextUnitValue>
  get() = TextUnitCalculation.of(this, TextUnitType.Em)
