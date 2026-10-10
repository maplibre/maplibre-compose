package org.maplibre.compose.expressions.dsl

import androidx.compose.ui.unit.TextUnitType
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.TextUnitCalculation
import org.maplibre.compose.expressions.value.AnyValue
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.CollatorValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.DpPaddingValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.EnumType
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
import org.maplibre.compose.expressions.value.VectorValue
import org.maplibre.compose.style.metersToDp

/** Returns a string describing the type of this expression. */
public fun Expression<*>.type(): Expression<ExpressionType> = call("typeof", this)

/**
 * Asserts that this is a list, optionally of items of one [type] and of one [length].
 *
 * [type] is [ExpressionType.String], [ExpressionType.Number], or [ExpressionType.Boolean]. A
 * [length] needs a [type]. Both are plain values because MapLibre reads them when the style loads.
 *
 * If, when the input expression is evaluated, it is not of the asserted type, then this assertion
 * will cause the whole expression to be aborted. A null input, such as a missing property, aborts.
 */
public fun Expression<*>.asList(
  type: ExpressionType? = null,
  length: Int? = null,
): Expression<ListValue<AnyValue?>> {
  require(
    type == null ||
      type == ExpressionType.String ||
      type == ExpressionType.Number ||
      type == ExpressionType.Boolean
  ) {
    "The item type of a list assertion must be String, Number, or Boolean, was $type"
  }
  require(length == null || type != null) { "A list assertion with a length needs an item type" }
  return call("array", listOfNotNull(type?.let { const(it) }, length?.let { const(it) }, this))
}

/**
 * Asserts that this is a list of numbers, optionally with a specific [length].
 *
 * If, when the input expression is evaluated, it is not of the asserted type, then this assertion
 * will cause the whole expression to be aborted. A null input, such as a missing property, aborts.
 *
 * @param U the unit type of the numbers. For dimensionless quantities, use [Number].
 */
public fun <U> Expression<*>.asVector(length: Int? = null): Expression<VectorValue<U>> =
  asList(ExpressionType.Number, length).cast()

/**
 * Asserts that this is a list of numbers of length 2.
 *
 * If, when the input expression is evaluated, it is not of the asserted type, then this assertion
 * will cause the whole expression to be aborted. A null input, such as a missing property, aborts.
 */
public fun Expression<*>.asOffset(): Expression<FloatOffsetValue> =
  asList(ExpressionType.Number, 2).cast()

/**
 * Asserts that this is a list of numbers of length 2.
 *
 * If, when the input expression is evaluated, it is not of the asserted type, then this assertion
 * will cause the whole expression to be aborted. A null input, such as a missing property, aborts.
 */
public fun Expression<*>.asDpOffset(): Expression<DpOffsetValue> =
  asList(ExpressionType.Number, 2).cast()

/**
 * Asserts that this is a list of numbers. Padding takes one to four numbers.
 *
 * If, when the input expression is evaluated, it is not of the asserted type, then this assertion
 * will cause the whole expression to be aborted. A null input, such as a missing property, aborts.
 */
public fun Expression<*>.asPadding(): Expression<DpPaddingValue> =
  asList(ExpressionType.Number).cast()

/**
 * Asserts that this value is a string.
 *
 * In case this expression is not a string, each of the [fallbacks] is evaluated in order until a
 * string is obtained. If none of the inputs are strings, the expression is an error. A missing
 * property is a null input and needs a fallback. Where the map property accepts a null,
 * [cast][org.maplibre.compose.expressions.ast.Expression.cast] to a nullable type instead.
 */
public fun Expression<*>.asString(vararg fallbacks: Expression<*>): Expression<StringValue> =
  call("string", this, *fallbacks)

/**
 * Asserts that this expression resolves to one of the named values of [type]. Pass the style type's
 * companion, such as `LineCap`, to obtain an expression of that type.
 *
 * Each of the [fallbacks] is evaluated in order until a value matches. If neither this expression
 * nor a fallback matches, the expression is an error. Membership in [EnumType.entries] is checked
 * when MapLibre evaluates the expression.
 */
public fun <T : EnumValue> Expression<*>.asEnum(
  type: EnumType<T>,
  vararg fallbacks: Expression<*>,
): Expression<T> {
  val entries = const(type.entries)
  val conditions =
    (listOf(this) + fallbacks).map { candidate ->
      condition(entries.contains(candidate), candidate)
    }
  return switch(conditions, fallback = nil()).asString().cast()
}

/**
 * Asserts that this value is a number.
 *
 * In case this expression is not a number, each of the [fallbacks] is evaluated in order until a
 * number is obtained. If none of the inputs are numbers, the expression is an error. A missing
 * property is a null input and needs a fallback. Where the map property accepts a null,
 * [cast][org.maplibre.compose.expressions.ast.Expression.cast] to a nullable type instead.
 */
public fun Expression<*>.asNumber(vararg fallbacks: Expression<*>): Expression<FloatValue> =
  call("number", this, *fallbacks)

/**
 * Asserts that this value is a boolean.
 *
 * In case this expression is not a boolean, each of the [fallbacks] is evaluated in order until a
 * boolean is obtained. If none of the inputs are booleans, the expression is an error. A missing
 * property is a null input and needs a fallback. Where the map property accepts a null,
 * [cast][org.maplibre.compose.expressions.ast.Expression.cast] to a nullable type instead.
 */
public fun Expression<*>.asBoolean(vararg fallbacks: Expression<*>): Expression<BooleanValue> =
  call("boolean", this, *fallbacks)

/**
 * Asserts that this value is a map.
 *
 * In case this expression is not a map, each of the [fallbacks] is evaluated in order until a map
 * is obtained. If none of the inputs are maps, the expression is an error. A missing property is a
 * null input and needs a fallback. Where the map property accepts a null,
 * [cast][org.maplibre.compose.expressions.ast.Expression.cast] to a nullable type instead.
 */
public fun Expression<*>.asMap(vararg fallbacks: Expression<*>): Expression<MapValue<AnyValue>> =
  call("object", this, *fallbacks)

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
  call(
    "collator",
    options(
      "case-sensitive" to caseSensitive,
      "diacritic-sensitive" to diacriticSensitive,
      "locale" to locale,
    ),
  )

/**
 * Returns a collator with MapLibre's defaults: case-insensitive, diacritic-insensitive, and the
 * default locale.
 */
public fun collator(): Expression<CollatorValue> = call("collator", options())

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
  call(
    "number-format",
    this,
    options(
      "locale" to locale,
      "currency" to currency,
      "min-fraction-digits" to minFractionDigits,
      "max-fraction-digits" to maxFractionDigits,
    ),
  )

/** Converts this number to a string in the default locale. */
public fun Expression<NumberValue<*>>.formatToString(): Expression<StringValue> =
  call("number-format", this, options())

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
 * function of the ECMAScript Language Specification. A null input becomes an empty string.
 */
public fun Expression<*>.convertToString(): Expression<StringValue> = call("to-string", this)

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
  call("to-number", this, *fallbacks)

/**
 * Converts this expression to a boolean expression.
 *
 * The result is `false` when then this is an empty string, `0`, `false`,`null` or `NaN`; otherwise
 * it is `true`.
 */
public fun Expression<*>.convertToBoolean(): Expression<BooleanValue> = call("to-boolean", this)

/**
 * Converts this expression to a color expression.
 *
 * In case this expression cannot be converted to a color, each of the [fallbacks] is evaluated in
 * order until the first successful conversion is obtained. If none of the inputs can be converted,
 * the expression is an error.
 */
public fun Expression<*>.convertToColor(vararg fallbacks: Expression<*>): Expression<ColorValue> =
  call("to-color", this, *fallbacks)

/** Converts a numeric [Expression] to a [DpValue] expression. */
public val Expression<FloatValue>.dp: Expression<DpValue>
  get() = this.cast()

/**
 * Converts a numeric [Expression] of meters on the ground to a [DpValue] expression: the size those
 * meters have on screen at the current zoom and the camera's latitude. Use it to draw features at
 * their real-world size, for example a road 10 meters wide or a circle with a 50 meter radius.
 *
 * The conversion uses the scale at the center of the map, so with pitch or at very low zoom,
 * features far from the center are drawn at the center's scale.
 *
 * The result must be the whole value of a layer property, not part of another expression. Use it in
 * paint properties such as line width or circle radius. Layout properties also accept it, but the
 * map can then reload the source's tiles as the camera moves north or south.
 */
public val Expression<FloatValue>.meters: Expression<DpValue>
  get() = metersToDp(this)

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
