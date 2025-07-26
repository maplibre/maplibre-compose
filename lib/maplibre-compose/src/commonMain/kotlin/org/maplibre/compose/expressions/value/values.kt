package org.maplibre.compose.expressions.value

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import kotlin.time.Duration
import org.maplibre.compose.expressions.ast.StringLiteral

/**
 * Represents a value that an [Expression] can resolve to. Many of these types are never actually
 * instantiated at runtime; they're only used as type parameters to hint at the type of an
 * [Expression].
 */
public sealed interface ExpressionValue

/** Represents an [ExpressionValue] that resolves to a true or false value. See [const]. */
public sealed interface BooleanValue : ExpressionValue, EquatableValue

/**
 * Represents an [ExpressionValue] that resolves to a numeric quantity. Corresponds to numbers in
 * the JSON style spec. Use [const] to create a literal [NumberValue].
 *
 * @param U the unit type of the number. For dimensionless quantities, use [Number].
 */
public sealed interface NumberValue<U> :
  ExpressionValue,
  MatchableValue,
  InterpolatableValue<U>,
  ComparableValue<NumberValue<U>>,
  EquatableValue

/** Represents an [ExpressionValue] that resolves to a dimensionless quantity. See [const]. */
public typealias FloatValue = NumberValue<Number>

/**
 * Represents an [ExpressionValue] that resolves to an integer dimensionless quantity. See [const].
 */
public sealed interface IntValue : NumberValue<Number>

/**
 * Represents an [ExpressionValue] that resolves to device-independent pixels ([Dp]). See [const].
 */
public typealias DpValue = NumberValue<Dp>

/**
 * Represents an [ExpressionValue] that resolves to scalable pixels or em ([TextUnit]). See [const].
 *
 * Which unit it resolves to is determined by the style property it's used in.
 */
public typealias TextUnitValue = NumberValue<TextUnit>

/**
 * Represents an [ExpressionValue] that resolves to an amount of time with millisecond precision
 * ([Duration]). See [const].
 */
public typealias MillisecondsValue = NumberValue<Duration>

/** Represents an [ExpressionValue] that resolves to a string value. See [const]. */
public sealed interface StringValue :
  ExpressionValue,
  MatchableValue,
  ComparableValue<StringValue>,
  EquatableValue,
  FormattableValue,
  FormattedValue

/**
 * Represents an [ExpressionValue] that resolves to an enum string. See [const].
 *
 * @param T The [EnumValue] descendent type that this value represents.
 */
public sealed interface EnumValue<out T> : StringValue {
  /** The string expression representing this enum value. */
  public val literal: StringLiteral
}

/** Represents an [ExpressionValue] that resolves to a [Color] value. See [const]. */
public sealed interface ColorValue : ExpressionValue, InterpolatableValue<ColorValue>

/**
 * Represents an [ExpressionValue] that resolves to a map value (corresponds to a JSON object). See
 * [const].
 */
public sealed interface MapValue<@Suppress("unused") out T : ExpressionValue> : ExpressionValue

/**
 * Represents an [ExpressionValue] that resolves to a list value (corresponds to a JSON array). See
 * [const].
 */
public sealed interface ListValue<out T : ExpressionValue> : ExpressionValue

/**
 * Represents an [ExpressionValue] that resolves to a list value (corresponds to a JSON array) of
 * alternating types.
 */
public sealed interface AlternatingListValue<
  @Suppress("unused")
  out T1 : ExpressionValue,
  @Suppress("unused")
  out T2 : ExpressionValue,
> : ListValue<ExpressionValue>

/**
 * Represents an [ExpressionValue] that resolves to an alternating list of [SymbolAnchor] and
 * [FloatOffsetValue].
 *
 * See [SymbolLayer][org.maplibre.compose.layers.SymbolLayer].
 */
public typealias TextVariableAnchorOffsetValue =
  AlternatingListValue<SymbolAnchor, FloatOffsetValue>

/**
 * Represents an [ExpressionValue] that resolves to a list of numbers.
 *
 * @param U the unit type of the number. For dimensionless quantities, use [Number].
 */
public sealed interface VectorValue<U> :
  ListValue<NumberValue<U>>, InterpolatableValue<VectorValue<U>>

/**
 * Represents an [ExpressionValue] that reoslves to a 2D vector in some unit.
 *
 * @param U the unit type of the offset. For dimensionless quantities, use [Number].
 */
public sealed interface OffsetValue<U> : VectorValue<U>

/**
 * Represents an [ExpressionValue] that resolves to a 2D floating point offset without a particular
 * unit. ([Offset]). See [offset].
 */
public typealias FloatOffsetValue = OffsetValue<Number>

/**
 * Represents an [ExpressionValue] that resolves to a 2D floating point offset in device-independent
 * pixels ([DpOffset]). See [offset].
 */
public typealias DpOffsetValue = OffsetValue<Dp>

/**
 * Represents an [ExpressionValue] that resolves to a 2D floating point offset in scalable pixels or
 * em ([TextUnit]). See [offset].
 */
public typealias TextUnitOffsetValue = OffsetValue<TextUnit>

/**
 * Represents an [ExpressionValue] that resolves to an absolute (layout direction unaware) padding
 * applied along the edges inside a box ([PaddingValues.Absolute]). See [const].
 */
public sealed interface DpPaddingValue : VectorValue<Dp>

/**
 * Represents an [ExpressionValue] that resolves to a collator object for use in locale-dependent
 * comparison operations. See [collator].
 */
public sealed interface CollatorValue : ExpressionValue

/** Represents an [ExpressionValue] that resolves to a formatted string. See [format]. */
public sealed interface FormattedValue : ExpressionValue

/** Represents an [ExpressionValue] that resolves to a geometry object. */
public sealed interface GeoJsonValue : ExpressionValue

/** Represents an [ExpressionValue] that resolves to an image. See [image] */
public sealed interface ImageValue : ExpressionValue, FormattableValue

/**
 * Represents an [ExpressionValue] that resolves to an interpolation type. See [linear],
 * [exponential], and [cubicBezier].
 */
public sealed interface InterpolationValue : ExpressionValue
