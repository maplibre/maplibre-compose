package org.maplibre.compose.expressions.dsl

import kotlin.jvm.JvmName
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.CollatorValue
import org.maplibre.compose.expressions.value.IntValue
import org.maplibre.compose.expressions.value.ListValue
import org.maplibre.compose.expressions.value.StringValue

/** Returns whether this string contains the [substring]. */
@JvmName("containsString")
public fun Expression<StringValue>.contains(
  substring: Expression<StringValue>
): Expression<BooleanValue> = call("in", substring, this)

/** Returns whether this string contains the [substring]. */
@JvmName("containsString")
public fun Expression<StringValue>.contains(substring: String): Expression<BooleanValue> =
  contains(const(substring))

/** Returns whether this string contains the [substring]. */
@JvmName("containsString")
public fun String.contains(substring: Expression<StringValue>): Expression<BooleanValue> =
  const(this).contains(substring)

/**
 * Returns the first index at which the [substring] is located in this string, or `-1` if it cannot
 * be found. Accepts an optional [startIndex] from where to begin the search.
 */
@JvmName("indexOfString")
public fun Expression<StringValue>.indexOf(
  substring: Expression<StringValue>,
  startIndex: Expression<IntValue>? = null,
): Expression<IntValue> = call("index-of", listOfNotNull(substring, this, startIndex))

/**
 * Returns the first index at which the [substring] is located in this string, or `-1` if it cannot
 * be found. Accepts an optional [startIndex] from where to begin the search.
 */
@JvmName("indexOfString")
public fun String.indexOf(
  substring: Expression<StringValue>,
  startIndex: Expression<IntValue>? = null,
): Expression<IntValue> = const(this).indexOf(substring, startIndex)

/**
 * Returns the first index at which the [substring] is located in this string, or `-1` if it cannot
 * be found. Accepts an optional [startIndex] from where to begin the search.
 */
@JvmName("indexOfString")
public fun Expression<StringValue>.indexOf(
  substring: String,
  startIndex: Int? = null,
): Expression<IntValue> =
  indexOf(substring = const(substring), startIndex = startIndex?.let { const(it) })

/**
 * Returns a substring from this string from the [startIndex] (inclusive) to the end of the string
 * if [endIndex] is not specified or `null`, otherwise to [endIndex] (exclusive).
 *
 * A UTF-16 surrogate pair counts as a single position.
 */
public fun Expression<StringValue>.substring(
  startIndex: Expression<IntValue>,
  endIndex: Expression<IntValue>? = null,
): Expression<StringValue> = call("slice", listOfNotNull(this, startIndex, endIndex))

/**
 * Returns a substring from this string from the [startIndex] (inclusive) to the end of the string
 * if [endIndex] is not specified or `null`, otherwise to [endIndex] (exclusive).
 *
 * A UTF-16 surrogate pair counts as a single position.
 */
public fun Expression<StringValue>.substring(
  startIndex: Int,
  endIndex: Int? = null,
): Expression<StringValue> =
  substring(startIndex = const(startIndex), endIndex = endIndex?.let { const(it) })

/**
 * Gets the length of this string.
 *
 * A UTF-16 surrogate pair counts as a single position.
 */
@JvmName("lengthOfString")
public fun Expression<StringValue>.length(): Expression<IntValue> = call("length", this)

/**
 * Returns `true` if this string is expected to render legibly. Returns `false` if this string
 * contains sections that cannot be rendered without potential loss of meaning (e.g. Indic scripts
 * that require complex text shaping).
 */
public fun Expression<StringValue>.isScriptSupported(): Expression<BooleanValue> =
  call("is-supported-script", this)

/**
 * Returns this string converted to uppercase. Follows the Unicode Default Case Conversion algorithm
 * and the locale-insensitive case mappings in the Unicode Character Database.
 */
public fun Expression<StringValue>.uppercase(): Expression<StringValue> = call("upcase", this)

/**
 * Returns this string converted to lowercase. Follows the Unicode Default Case Conversion algorithm
 * and the locale-insensitive case mappings in the Unicode Character Database.
 */
public fun Expression<StringValue>.lowercase(): Expression<StringValue> = call("downcase", this)

/** Concatenates this string expression with [other]. */
@JvmName("concat")
public operator fun Expression<StringValue>.plus(
  other: Expression<StringValue>
): Expression<StringValue> = call("concat", this, other)

/**
 * Returns the substrings formed by splitting this string at each occurrence of [separator].
 *
 * An empty [separator] splits this string into individual Unicode characters. Consecutive
 * separators produce empty strings in the result.
 */
public fun Expression<StringValue>.split(
  separator: Expression<StringValue>
): Expression<ListValue<StringValue>> = call("split", this, separator)

/**
 * Returns the substrings formed by splitting this string at each occurrence of [separator].
 *
 * An empty [separator] splits this string into individual Unicode characters. Consecutive
 * separators produce empty strings in the result.
 */
public fun Expression<StringValue>.split(separator: String): Expression<ListValue<StringValue>> =
  split(const(separator))

/**
 * Returns the substrings formed by splitting this string at each occurrence of [separator].
 *
 * An empty [separator] splits this string into individual Unicode characters. Consecutive
 * separators produce empty strings in the result.
 */
public fun String.split(separator: Expression<StringValue>): Expression<ListValue<StringValue>> =
  const(this).split(separator)

/**
 * Returns the IETF language tag of the locale being used by the provided [collator]. This can be
 * used to determine the default system locale, or to determine if a requested locale was
 * successfully loaded.
 */
public fun resolvedLocale(collator: Expression<CollatorValue>): Expression<StringValue> =
  call("resolved-locale", collator)
