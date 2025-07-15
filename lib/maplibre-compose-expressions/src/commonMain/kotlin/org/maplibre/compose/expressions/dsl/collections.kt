package org.maplibre.compose.expressions.dsl

import kotlin.jvm.JvmName
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.FunctionCall
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.IntValue
import org.maplibre.compose.expressions.value.ListValue
import org.maplibre.compose.expressions.value.MapValue
import org.maplibre.compose.expressions.value.StringValue

/** Returns the item at [index]. */
@JvmName("getAt")
public operator fun <T : ExpressionValue> Expression<ListValue<T>>.get(
  index: Expression<IntValue>
): Expression<T> = FunctionCall.of("at", index, this).cast()

/** Returns the item at [index]. */
@JvmName("getAt")
public operator fun <T : ExpressionValue> Expression<ListValue<T>>.get(index: Int): Expression<T> =
  get(const(index))

/** Returns whether this list contains the [item]. */
@JvmName("containsList")
public fun <T : ExpressionValue> Expression<ListValue<T>>.contains(
  item: Expression<T>
): Expression<BooleanValue> = FunctionCall.of("in", item, this).cast()

/**
 * Returns the first index at which the [item] is located in this list, or `-1` if it cannot be
 * found. Accepts an optional [startIndex] from where to begin the search.
 */
@JvmName("indexOfList")
public fun <T : ExpressionValue> Expression<ListValue<T>>.indexOf(
  item: Expression<T>,
  startIndex: Expression<IntValue>? = null,
): Expression<IntValue> {
  val args = buildList {
    add(item)
    add(this@indexOf)
    startIndex?.let { add(it) }
  }
  return FunctionCall.of("index-of", *args.toTypedArray()).cast()
}

/**
 * Returns the first index at which the [item] is located in this list, or `-1` if it cannot be
 * found. Accepts an optional [startIndex] from where to begin the search.
 */
@JvmName("indexOfList")
public fun <T : ExpressionValue> Expression<ListValue<T>>.indexOf(
  item: Expression<T>,
  startIndex: Int? = null,
): Expression<IntValue> = indexOf(item, startIndex?.let { const(it) })

/**
 * Returns the items in this list from the [startIndex] (inclusive) to the end of this list if
 * [endIndex] is not specified or `null`, otherwise to [endIndex] (exclusive).
 */
public fun <T : ExpressionValue> Expression<ListValue<T>>.slice(
  startIndex: Expression<IntValue>,
  endIndex: Expression<IntValue>? = null,
): Expression<ListValue<T>> {
  val args = buildList {
    add(this@slice)
    add(startIndex)
    endIndex?.let { add(it) }
  }
  return FunctionCall.of("slice", *args.toTypedArray()).cast()
}

/**
 * Returns the items in this list from the [startIndex] (inclusive) to the end of this list if
 * [endIndex] is not specified or `null`, otherwise to [endIndex] (exclusive).
 */
public fun <T : ExpressionValue> Expression<ListValue<T>>.slice(
  startIndex: Int,
  endIndex: Int? = null,
): Expression<ListValue<T>> = slice(const(startIndex), endIndex?.let { const(it) })

/** Gets the length of a this list. */
@JvmName("lengthOfList")
public fun Expression<ListValue<*>>.length(): Expression<IntValue> =
  FunctionCall.of("length", this).cast()

/** Returns the value corresponding the given [key] or `null` if it is not present in this map. */
public operator fun <T : ExpressionValue> Expression<MapValue<T>>.get(
  key: Expression<StringValue>
): Expression<T> = FunctionCall.of("get", key, this).cast()

/** Returns the value corresponding the given [key] or `null` if it is not present in this map. */
public operator fun <T : ExpressionValue> Expression<MapValue<T>>.get(key: String): Expression<T> =
  get(const(key))

/** Returns whether the given [key] is in this map. */
public fun Expression<MapValue<*>>.has(key: Expression<StringValue>): Expression<BooleanValue> =
  FunctionCall.of("has", key, this).cast()

/** Returns whether the given [key] is in this map. */
public fun Expression<MapValue<*>>.has(key: String): Expression<BooleanValue> = has(const(key))
