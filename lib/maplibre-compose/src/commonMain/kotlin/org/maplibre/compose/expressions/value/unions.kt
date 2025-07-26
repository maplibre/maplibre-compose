package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.gt
import org.maplibre.compose.expressions.dsl.gte
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.lt
import org.maplibre.compose.expressions.dsl.lte
import org.maplibre.compose.expressions.dsl.neq
import org.maplibre.compose.expressions.dsl.switch

/** Represents and [Expression] that resolves to a value that can be an input to [format]. */
public sealed interface FormattableValue : ExpressionValue

/**
 * Represents an [Expression] that resolves to a value that can be compared for equality. See [eq]
 * and [neq].
 */
public sealed interface EquatableValue : ExpressionValue

/** Union type for an [Expression] that resolves to a value that can be matched. See [switch]. */
public sealed interface MatchableValue : ExpressionValue

/**
 * Union type for an [Expression] that resolves to a value that can be ordered with other values of
 * its type. See [gt], [lt], [gte], and [lte].
 *
 * @param T the type of the value that can be compared against for ordering.
 */
public sealed interface ComparableValue<T> : ExpressionValue

/**
 * Union type for an [Expression] that resolves to a value that can be interpolated. See
 * [interpolate].
 *
 * @param T the type of values that can be interpolated between.
 */
public sealed interface InterpolatableValue<T> : ExpressionValue
