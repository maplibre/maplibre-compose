package org.maplibre.compose.expressions.kotlin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import kotlin.time.Duration
import org.maplibre.compose.expressions.value.ExpressionType
import org.maplibre.compose.style.ProjectionTransition
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.ImageStretch
import org.maplibre.spatialk.geojson.GeoJsonObject

/** Marker for the `expr { }` receiver. Methods exist so the block type-checks as Kotlin. */
@DslMarker public annotation class ExprDsl

/** Interpolation curve used by [ExprScope.interpolate] and friends. */
public class Interpolation internal constructor()

/** Formatted text produced by [ExprScope.format]. */
public class FormattedText internal constructor()

/** One styled run inside [ExprScope.format]. */
public class FormattedSpan internal constructor()

/** Image reference produced by [ExprScope.image]. */
public class ImageRef internal constructor()

/** Locale collator produced by [ExprScope.collator]. */
public class Collator internal constructor()

/**
 * Receiver for [expr] lambdas. Properties and helpers return ordinary Kotlin types so `if`, `when`,
 * and operators type-check. The compiler plugin captures the calls; these stubs never run.
 */
@ExprDsl
public interface ExprScope {
  /** Current feature being rendered. */
  public val feature: FeatureExpr

  /**
   * Current map zoom. In layer paint properties, only valid as the input to [step]/[interpolate].
   */
  public val zoom: Double

  /** Heatmap kernel density. Only valid as the input to a heatmap layer color. */
  public val heatmapDensity: Double

  /** Terrain elevation in meters. Only valid as the input to a color-relief layer color. */
  public val elevation: Double

  /** MapLibre `ln2` operator, not the Kotlin `ln(2)` literal. */
  public val ln2: Double

  /** MapLibre `pi` operator. `kotlin.math.PI` still freezes as a composition-time number. */
  public val pi: Double

  /** MapLibre `e` operator. */
  public val e: Double

  public fun linear(): Interpolation

  public fun exponential(base: Number): Interpolation

  public fun cubicBezier(x1: Number, y1: Number, x2: Number, y2: Number): Interpolation

  public fun <T> interpolate(
    type: Interpolation,
    input: Double,
    vararg stops: Pair<Number, T>,
  ): T

  public fun <T> interpolateHcl(
    type: Interpolation,
    input: Double,
    vararg stops: Pair<Number, T>,
  ): T

  public fun <T> interpolateLab(
    type: Interpolation,
    input: Double,
    vararg stops: Pair<Number, T>,
  ): T

  public fun <T> step(input: Double, fallback: T, vararg stops: Pair<Number, T>): T

  public fun <T> match(input: Any?, vararg cases: Pair<Any, T>, fallback: T): T

  public fun <T> nil(): T

  public fun rgb(red: Number, green: Number, blue: Number, alpha: Number = 1.0): Color

  public fun Color.toRgba(): List<Double>

  public fun format(vararg spans: FormattedSpan): FormattedText

  public fun span(
    text: String,
    font: List<String>? = null,
    textColor: Color? = null,
    textSize: TextUnit? = null,
  ): FormattedSpan

  public fun span(image: ImageRef): FormattedSpan

  public fun image(name: String): ImageRef

  public fun image(
    bitmap: ImageBitmap,
    isSdf: Boolean = false,
    stretch: ImageStretch? = null,
  ): ImageRef

  public fun image(
    painter: Painter,
    size: DpSize? = null,
    drawAsSdf: Boolean = false,
    stretch: ImageStretch? = null,
    alpha: Float = 1f,
    colorFilter: ColorFilter? = null,
  ): ImageRef

  public fun <T, R> bind(name: String, value: T, body: (T) -> R): R

  public fun Any?.asNumber(vararg fallbacks: Number): Double

  public fun Any?.asString(vararg fallbacks: String): String

  public fun Any?.asBoolean(vararg fallbacks: Boolean): Boolean

  public fun Any?.asColor(vararg fallbacks: Any?): Color

  /**
   * Asserts the value is one of [entries] (style-spec strings, or [EnumValue] names via
   * `entries.map { it.literal.value }`). Reified `asEnum<T>()` is not possible on an interface.
   */
  public fun Any?.asEnum(entries: List<String>, vararg fallbacks: Any?): String

  public fun Any?.asMap(vararg fallbacks: Any?): Map<String, Any?>

  public fun Any?.asList(type: ExpressionType? = null, length: Int? = null): List<Any?>

  public fun Any?.asVector(length: Int? = null): List<Double>

  public fun Any?.asOffset(): Offset

  public fun Any?.asDpOffset(): DpOffset

  public fun Any?.asPadding(): DpPadding

  public fun Any?.convertToNumber(vararg fallbacks: Number): Double

  public fun Any?.convertToString(): String

  public fun Any?.convertToBoolean(): Boolean

  public fun Any?.convertToColor(vararg fallbacks: Any?): Color

  public fun Any?.typeOf(): String

  public val Number.dp: Dp

  public val Number.milliseconds: Duration

  public val Number.seconds: Duration

  public val Number.sp: TextUnit

  public val Number.em: TextUnit

  public fun collator(
    caseSensitive: Boolean = false,
    diacriticSensitive: Boolean = false,
    locale: String? = null,
  ): Collator

  public fun resolvedLocale(collator: Collator): String

  public fun String.isScriptSupported(): Boolean

  public fun eq(left: String, right: String, collator: Collator): Boolean

  public fun neq(left: String, right: String, collator: Collator): Boolean

  public fun gt(left: String, right: String, collator: Collator): Boolean

  public fun gte(left: String, right: String, collator: Collator): Boolean

  public fun lt(left: String, right: String, collator: Collator): Boolean

  public fun lte(left: String, right: String, collator: Collator): Boolean

  public fun Number.formatToString(
    locale: String? = null,
    currency: String? = null,
    minFractionDigits: Int? = null,
    maxFractionDigits: Int? = null,
  ): String

  public fun offset(x: Number, y: Number): Offset

  public fun offset(x: TextUnit, y: TextUnit): Offset

  public fun dpOffset(x: Dp, y: Dp): DpOffset

  public fun padding(left: Dp, top: Dp, right: Dp, bottom: Dp): DpPadding

  public fun projectionTransition(
    from: Any,
    to: Any,
    progress: Number,
  ): ProjectionTransition

  public fun textVariableAnchorOffset(vararg anchorsAndOffsets: Pair<Any, Offset>): List<Any>

  public operator fun Map<String, Any?>.get(key: String): Any?

  public fun Map<String, Any?>.has(key: String): Boolean
}

/**
 * Feature accessors typed as Kotlin values. Dynamic properties come back as [Any] and are converted
 * with [ExprScope.asNumber], [ExprScope.asString], and similar helpers.
 */
public interface FeatureExpr {
  public operator fun get(key: String): Any?

  public fun has(key: String): Boolean

  public fun properties(): Map<String, Any?>

  public fun state(key: String): Any?

  public fun geometryType(): String

  public fun id(): Any?

  public fun lineProgress(): Double

  public fun accumulated(): Any?

  public fun within(geometry: GeoJsonObject): Boolean

  public fun distance(geometry: GeoJsonObject): Double

  public fun number(key: String): Double

  public fun string(key: String): String

  public fun boolean(key: String): Boolean
}

@Suppress("unused")
internal object ExprScopeStubs : ExprScope {
  override val feature: FeatureExpr = FeatureExprStubs
  override val zoom: Double
    get() = pluginRequired()

  override val heatmapDensity: Double
    get() = pluginRequired()

  override val elevation: Double
    get() = pluginRequired()

  override val ln2: Double
    get() = pluginRequired()

  override val pi: Double
    get() = pluginRequired()

  override val e: Double
    get() = pluginRequired()

  override fun linear(): Interpolation = pluginRequired()

  override fun exponential(base: Number): Interpolation = pluginRequired()

  override fun cubicBezier(x1: Number, y1: Number, x2: Number, y2: Number): Interpolation =
    pluginRequired()

  override fun <T> interpolate(
    type: Interpolation,
    input: Double,
    vararg stops: Pair<Number, T>,
  ): T = pluginRequired()

  override fun <T> interpolateHcl(
    type: Interpolation,
    input: Double,
    vararg stops: Pair<Number, T>,
  ): T = pluginRequired()

  override fun <T> interpolateLab(
    type: Interpolation,
    input: Double,
    vararg stops: Pair<Number, T>,
  ): T = pluginRequired()

  override fun <T> step(input: Double, fallback: T, vararg stops: Pair<Number, T>): T =
    pluginRequired()

  override fun <T> match(input: Any?, vararg cases: Pair<Any, T>, fallback: T): T = pluginRequired()

  override fun <T> nil(): T = pluginRequired()

  override fun rgb(red: Number, green: Number, blue: Number, alpha: Number): Color =
    pluginRequired()

  override fun Color.toRgba(): List<Double> = pluginRequired()

  override fun format(vararg spans: FormattedSpan): FormattedText = pluginRequired()

  override fun span(
    text: String,
    font: List<String>?,
    textColor: Color?,
    textSize: TextUnit?,
  ): FormattedSpan = pluginRequired()

  override fun span(image: ImageRef): FormattedSpan = pluginRequired()

  override fun image(name: String): ImageRef = pluginRequired()

  override fun image(bitmap: ImageBitmap, isSdf: Boolean, stretch: ImageStretch?): ImageRef =
    pluginRequired()

  override fun image(
    painter: Painter,
    size: DpSize?,
    drawAsSdf: Boolean,
    stretch: ImageStretch?,
    alpha: Float,
    colorFilter: ColorFilter?,
  ): ImageRef = pluginRequired()

  override fun <T, R> bind(name: String, value: T, body: (T) -> R): R = pluginRequired()

  override fun Any?.asNumber(vararg fallbacks: Number): Double = pluginRequired()

  override fun Any?.asString(vararg fallbacks: String): String = pluginRequired()

  override fun Any?.asBoolean(vararg fallbacks: Boolean): Boolean = pluginRequired()

  override fun Any?.asColor(vararg fallbacks: Any?): Color = pluginRequired()

  override fun Any?.asEnum(entries: List<String>, vararg fallbacks: Any?): String = pluginRequired()

  override fun Any?.asMap(vararg fallbacks: Any?): Map<String, Any?> = pluginRequired()

  override fun Any?.asList(type: ExpressionType?, length: Int?): List<Any?> = pluginRequired()

  override fun Any?.asVector(length: Int?): List<Double> = pluginRequired()

  override fun Any?.asOffset(): Offset = pluginRequired()

  override fun Any?.asDpOffset(): DpOffset = pluginRequired()

  override fun Any?.asPadding(): DpPadding = pluginRequired()

  override fun Any?.convertToNumber(vararg fallbacks: Number): Double = pluginRequired()

  override fun Any?.convertToString(): String = pluginRequired()

  override fun Any?.convertToBoolean(): Boolean = pluginRequired()

  override fun Any?.convertToColor(vararg fallbacks: Any?): Color = pluginRequired()

  override fun Any?.typeOf(): String = pluginRequired()

  override val Number.dp: Dp
    get() = pluginRequired()

  override val Number.milliseconds: Duration
    get() = pluginRequired()

  override val Number.seconds: Duration
    get() = pluginRequired()

  override val Number.sp: TextUnit
    get() = pluginRequired()

  override val Number.em: TextUnit
    get() = pluginRequired()

  override fun collator(
    caseSensitive: Boolean,
    diacriticSensitive: Boolean,
    locale: String?,
  ): Collator = pluginRequired()

  override fun resolvedLocale(collator: Collator): String = pluginRequired()

  override fun String.isScriptSupported(): Boolean = pluginRequired()

  override fun eq(left: String, right: String, collator: Collator): Boolean = pluginRequired()

  override fun neq(left: String, right: String, collator: Collator): Boolean = pluginRequired()

  override fun gt(left: String, right: String, collator: Collator): Boolean = pluginRequired()

  override fun gte(left: String, right: String, collator: Collator): Boolean = pluginRequired()

  override fun lt(left: String, right: String, collator: Collator): Boolean = pluginRequired()

  override fun lte(left: String, right: String, collator: Collator): Boolean = pluginRequired()

  override fun Number.formatToString(
    locale: String?,
    currency: String?,
    minFractionDigits: Int?,
    maxFractionDigits: Int?,
  ): String = pluginRequired()

  override fun offset(x: Number, y: Number): Offset = pluginRequired()

  override fun offset(x: TextUnit, y: TextUnit): Offset = pluginRequired()

  override fun dpOffset(x: Dp, y: Dp): DpOffset = pluginRequired()

  override fun padding(left: Dp, top: Dp, right: Dp, bottom: Dp): DpPadding = pluginRequired()

  override fun projectionTransition(
    from: Any,
    to: Any,
    progress: Number,
  ): ProjectionTransition = pluginRequired()

  override fun textVariableAnchorOffset(vararg anchorsAndOffsets: Pair<Any, Offset>): List<Any> =
    pluginRequired()

  override fun Map<String, Any?>.get(key: String): Any? = pluginRequired()

  override fun Map<String, Any?>.has(key: String): Boolean = pluginRequired()
}

@Suppress("unused")
internal object FeatureExprStubs : FeatureExpr {
  override fun get(key: String): Any? = pluginRequired()

  override fun has(key: String): Boolean = pluginRequired()

  override fun properties(): Map<String, Any?> = pluginRequired()

  override fun state(key: String): Any? = pluginRequired()

  override fun geometryType(): String = pluginRequired()

  override fun id(): Any? = pluginRequired()

  override fun lineProgress(): Double = pluginRequired()

  override fun accumulated(): Any? = pluginRequired()

  override fun within(geometry: GeoJsonObject): Boolean = pluginRequired()

  override fun distance(geometry: GeoJsonObject): Double = pluginRequired()

  override fun number(key: String): Double = pluginRequired()

  override fun string(key: String): String = pluginRequired()

  override fun boolean(key: String): Boolean = pluginRequired()
}
