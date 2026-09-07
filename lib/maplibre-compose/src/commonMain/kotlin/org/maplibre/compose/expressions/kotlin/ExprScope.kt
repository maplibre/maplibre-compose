package org.maplibre.compose.expressions.kotlin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import org.maplibre.compose.util.DpPadding

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

  public fun rgb(red: Number, green: Number, blue: Number, alpha: Number = 1.0): Color

  public fun Color.toRgba(): List<Double>

  public fun format(vararg spans: FormattedSpan): FormattedText

  public fun span(
    text: String,
    font: List<String>? = null,
    textColor: Color? = null,
    textSize: TextUnit? = null,
  ): FormattedSpan

  public fun image(name: String): ImageRef

  public fun image(bitmap: ImageBitmap): ImageRef

  public fun image(painter: Painter): ImageRef

  public fun <T, R> bind(name: String, value: T, body: (T) -> R): R

  public fun Any?.asNumber(vararg fallbacks: Number): Double

  public fun Any?.asString(vararg fallbacks: String): String

  public fun Any?.asBoolean(vararg fallbacks: Boolean): Boolean

  public fun Any?.asColor(vararg fallbacks: String): Color

  public fun Any?.asMap(): Map<String, Any?>

  public fun Any?.asList(): List<Any?>

  public fun Any?.convertToNumber(vararg fallbacks: Number): Double

  public fun Any?.convertToString(): String

  public fun Any?.convertToBoolean(): Boolean

  public fun Any?.convertToColor(vararg fallbacks: String): Color

  public fun Any?.typeOf(): String

  public fun collator(
    caseSensitive: Boolean = false,
    diacriticSensitive: Boolean = false,
    locale: String? = null,
  ): Collator

  public fun Number.formatToString(
    locale: String? = null,
    currency: String? = null,
    minFractionDigits: Int? = null,
    maxFractionDigits: Int? = null,
  ): String

  public fun offset(x: Number, y: Number): Offset

  public fun dpOffset(x: Dp, y: Dp): DpOffset

  public fun padding(left: Dp, top: Dp, right: Dp, bottom: Dp): DpPadding

  public fun textVariableAnchorOffset(vararg anchorsAndOffsets: Pair<Any, Offset>): List<Any>
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

  public fun within(geometry: Any): Boolean

  public fun distance(geometry: Any): Double

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

  override fun image(name: String): ImageRef = pluginRequired()

  override fun image(bitmap: ImageBitmap): ImageRef = pluginRequired()

  override fun image(painter: Painter): ImageRef = pluginRequired()

  override fun <T, R> bind(name: String, value: T, body: (T) -> R): R = pluginRequired()

  override fun Any?.asNumber(vararg fallbacks: Number): Double = pluginRequired()

  override fun Any?.asString(vararg fallbacks: String): String = pluginRequired()

  override fun Any?.asBoolean(vararg fallbacks: Boolean): Boolean = pluginRequired()

  override fun Any?.asColor(vararg fallbacks: String): Color = pluginRequired()

  override fun Any?.asMap(): Map<String, Any?> = pluginRequired()

  override fun Any?.asList(): List<Any?> = pluginRequired()

  override fun Any?.convertToNumber(vararg fallbacks: Number): Double = pluginRequired()

  override fun Any?.convertToString(): String = pluginRequired()

  override fun Any?.convertToBoolean(): Boolean = pluginRequired()

  override fun Any?.convertToColor(vararg fallbacks: String): Color = pluginRequired()

  override fun Any?.typeOf(): String = pluginRequired()

  override fun collator(
    caseSensitive: Boolean,
    diacriticSensitive: Boolean,
    locale: String?,
  ): Collator = pluginRequired()

  override fun Number.formatToString(
    locale: String?,
    currency: String?,
    minFractionDigits: Int?,
    maxFractionDigits: Int?,
  ): String = pluginRequired()

  override fun offset(x: Number, y: Number): Offset = pluginRequired()

  override fun dpOffset(x: Dp, y: Dp): DpOffset = pluginRequired()

  override fun padding(left: Dp, top: Dp, right: Dp, bottom: Dp): DpPadding = pluginRequired()

  override fun textVariableAnchorOffset(vararg anchorsAndOffsets: Pair<Any, Offset>): List<Any> =
    pluginRequired()
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

  override fun within(geometry: Any): Boolean = pluginRequired()

  override fun distance(geometry: Any): Double = pluginRequired()

  override fun number(key: String): Double = pluginRequired()

  override fun string(key: String): String = pluginRequired()

  override fun boolean(key: String): Boolean = pluginRequired()
}
