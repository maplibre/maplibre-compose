package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.gt
import org.maplibre.compose.expressions.dsl.gte
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.lt
import org.maplibre.compose.expressions.dsl.lte
import org.maplibre.compose.expressions.dsl.neq
import org.maplibre.compose.expressions.dsl.switch

/**
 * Represents an [Expression] that resolves to a value that can be an input to [format].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface FormattableValue : ExpressionValue

/**
 * Represents an [Expression] that resolves to a value that can be compared for equality. See [eq]
 * and [neq].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface EquatableValue : ExpressionValue

/**
 * Union type for an [Expression] that resolves to a value that can be matched. See [switch].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface MatchableValue : ExpressionValue

/**
 * Union type for an [Expression] that resolves to a value that can be ordered with other values of
 * its type. See [gt], [lt], [gte], and [lte].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 *
 * @param T the type of the value that can be compared against for ordering.
 */
public sealed interface ComparableValue<T> : ExpressionValue

/**
 * Union type for an [Expression] that resolves to a value that can be interpolated. See
 * [interpolate].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 *
 * @param T the type of values that can be interpolated between.
 */
public sealed interface InterpolatableValue<T> : ExpressionValue

/**
 * Union type for an [Expression] that resolves to either a single number or a list of numbers. The
 * style spec names this type `numberArray`. See [const].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 *
 * @param U the unit type of the numbers. For dimensionless quantities, use `Number`.
 */
public sealed interface FloatOrVectorValue<@Suppress("unused") out U> : ExpressionValue
