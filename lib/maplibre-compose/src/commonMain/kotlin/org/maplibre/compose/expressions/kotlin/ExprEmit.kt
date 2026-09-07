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
import androidx.compose.ui.unit.TextUnitType
import kotlin.time.Duration
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.FunctionCall
import org.maplibre.compose.expressions.ast.Options
import org.maplibre.compose.expressions.ast.TextUnitCalculation
import org.maplibre.compose.expressions.ast.TextUnitOffsetCalculation
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.value.EnumValue
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.style.ProjectionTransition
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.ImageStretch
import org.maplibre.spatialk.geojson.GeoJsonObject

/**
 * Stable call target for the compiler plugin. Each method has a simple JVM signature so IR
 * generation does not have to pick among DSL overloads.
 */
public object ExprEmit {
  public fun lit(value: Any?): Expression<*> =
    when (value) {
      null -> nil()
      // Kotlin/JS keeps booleans as primitive `boolean`. `is Boolean` does not match that box,
      // so `lit(true)` would fall through and return nothing. Equality does match.
      true -> const(true)
      false -> const(false)
      is Expression<*> -> value
      is Boolean -> const(value)
      is Int -> const(value)
      is Long -> const(value.toInt())
      is Float -> const(value)
      is Double -> const(value.toFloat())
      is String -> const(value)
      is Color -> const(value)
      is Dp -> const(value)
      is DpOffset -> const(value)
      is Offset -> const(value)
      is DpPadding -> const(value)
      is TextUnit -> const(value)
      is Duration -> const(value)
      is ProjectionTransition -> const(value)
      is EnumValue<*> -> value.literal
      is GeoJsonObject -> const(value)
      is ImageBitmap -> image(value)
      is Painter -> image(value)
      is List<*> -> listLiteral(value)
      else ->
        error(
          "Cannot capture ${value::class.simpleName} as an expression literal. " +
            "Pass a Boolean, Number, String, Color, Dp, Offset, Duration, enum, " +
            "GeoJSON, list of those, or an Expression."
        )
    }

  public fun op(name: String, args: List<*>): Expression<*> =
    FunctionCall.of(name, args.map { it as Expression<*> })

  public fun match(
    input: Expression<*>,
    fallback: Expression<*>,
    labelsAndOutputs: List<*>,
  ): Expression<*> {
    val labels = labelsAndOutputs.map { it as Expression<*> }
    val args =
      buildList(labels.size + 2) {
        add(input)
        addAll(labels)
        add(fallback)
      }
    val caseCount = labels.size / 2
    return FunctionCall.of(
      "match",
      args,
      isLiteralArg = { i -> i in 1..(caseCount * 2) && i % 2 == 1 },
    )
  }

  public fun interpolate(
    kind: String,
    type: Expression<*>,
    input: Expression<*>,
    stops: List<*>,
  ): Expression<*> {
    val args =
      buildList(stops.size + 2) {
        add(type)
        add(input)
        addAll(stops.map { it as Expression<*> })
      }
    return FunctionCall.of(kind, args)
  }

  public fun step(input: Expression<*>, fallback: Expression<*>, stops: List<*>): Expression<*> {
    val args =
      buildList(stops.size + 2) {
        add(input)
        add(fallback)
        addAll(stops.map { it as Expression<*> })
      }
    return FunctionCall.of("step", args)
  }

  public fun formatSpans(valuesAndOptions: List<*>): Expression<*> =
    FunctionCall.of("format", valuesAndOptions.map { it as Expression<*> })

  public fun spanOptions(
    textFont: Expression<*>?,
    textColor: Expression<*>?,
    textSize: Expression<*>?,
  ): Expression<*> = Options.build {
    textFont?.let { put("text-font", it) }
    textColor?.let { put("text-color", it) }
    textSize?.let { put("font-scale", it) }
  }

  public fun namedOptions(keysAndValues: List<*>): Expression<*> = Options.build {
    var i = 0
    while (i < keysAndValues.size) {
      val key = keysAndValues[i] as String
      val value = keysAndValues[i + 1] as Expression<*>?
      if (value != null) put(key, value)
      i += 2
    }
  }

  public fun textUnit(value: Expression<*>, type: String): Expression<*> {
    val unit =
      when (type.lowercase()) {
        "sp" -> TextUnitType.Sp
        "em" -> TextUnitType.Em
        else -> error("Text unit type must be sp or em, was $type")
      }
    return TextUnitCalculation.of(value.cast<FloatValue>(), unit)
  }

  public fun textUnitOffset(x: TextUnit, y: TextUnit): Expression<*> =
    TextUnitOffsetCalculation.of(x, y)

  public fun imageName(name: Expression<*>): Expression<*> = image(name.cast())

  public fun imageBitmap(
    bitmap: ImageBitmap,
    isSdf: Boolean,
    stretch: ImageStretch?,
  ): Expression<*> = image(bitmap, isSdf, stretch)

  public fun numberOffset(x: Number, y: Number): Expression<*> =
    const(Offset(x.toFloat(), y.toFloat()))

  public fun dpOffset(x: Dp, y: Dp): Expression<*> = const(DpOffset(x, y))

  public fun padding(left: Dp, top: Dp, right: Dp, bottom: Dp): Expression<*> =
    const(DpPadding(left = left, top = top, right = right, bottom = bottom))

  public fun projectionTransition(from: Any, to: Any, progress: Number): Expression<*> =
    const(
      ProjectionTransition(
        from as org.maplibre.compose.expressions.value.ProjectionType,
        to as org.maplibre.compose.expressions.value.ProjectionType,
        progress.toFloat(),
      )
    )

  public fun textVariableAnchorOffset(pairs: List<*>): Expression<*> {
    val typed = pairs.map { pair ->
      val (anchor, offset) = pair as Pair<*, *>
      (anchor as org.maplibre.compose.expressions.value.SymbolAnchor) to (offset as Offset)
    }
    return org.maplibre.compose.expressions.dsl.textVariableAnchorOffset(*typed.toTypedArray())
  }

  public fun imagePainter(
    painter: Painter,
    size: DpSize?,
    drawAsSdf: Boolean,
    stretch: ImageStretch?,
    alpha: Float,
    colorFilter: ColorFilter?,
  ): Expression<*> = image(painter, size, drawAsSdf, stretch, alpha, colorFilter)

  public fun formattedSpan(
    value: Expression<*>,
    textFont: String?,
    textColor: Color?,
    textSize: TextUnit?,
  ): Expression<*> =
    span(
        value = value.cast(),
        textFont = textFont?.let { const(it) },
        textColor = textColor?.let { const(it) },
        textSize = textSize?.let { const(it) },
      )
      .value

  @Suppress("UNCHECKED_CAST")
  private fun listLiteral(values: List<*>): Expression<*> {
    if (values.isEmpty()) return const(emptyList<String>())
    return when (val first = values.first()) {
      is String -> const(values as List<String>)
      is Number -> const(values as List<Number>)
      is EnumValue<*> -> const(values.map { (it as EnumValue<*>).literal })
      is Expression<*> -> {
        val literals = values.map { it as Expression<*> }
        if (literals.all { it is org.maplibre.compose.expressions.ast.Literal<*, *> }) {
          const(literals as List<org.maplibre.compose.expressions.ast.Literal<ExpressionValue, *>>)
        } else {
          error("List literals must contain only compile-time values")
        }
      }
      else -> error("Cannot capture a list of ${first?.let { it::class.simpleName }}")
    }
  }
}
