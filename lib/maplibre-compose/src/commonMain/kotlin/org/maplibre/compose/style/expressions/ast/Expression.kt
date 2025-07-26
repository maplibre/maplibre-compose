package org.maplibre.compose.style.expressions.ast

import org.maplibre.compose.style.expressions.value.ExpressionValue

/**
 * An [Expression] that evaluates to a value of type [T].
 *
 * The functions to create expressions are defined in the
 * [`org.maplibre.compose.style.expressions.dsl`](https://maplibre.org/maplibre-compose/api/lib/maplibre-compose/org.maplibre.compose.style.expressions.dsl/index.html)
 * package.
 *
 * Most functions are named the same as in the
 * [MapLibre style specification](https://maplibre.org/maplibre-style-spec/expressions/), a few have
 * been renamed to be Kotlin-idiomatic or made into extension functions (to an [Expression]).
 *
 * # Function overview
 *
 * ### Literals
 * - [const][org.maplibre.compose.style.expressions.dsl.const] - literal expression
 * - [nil][org.maplibre.compose.style.expressions.dsl.nil] - literal `null` expression
 *
 * ### Decision
 * - [switch][org.maplibre.compose.style.expressions.dsl.switch] - if-else / switch-case
 * - [coalesce][org.maplibre.compose.style.expressions.dsl.coalesce] - get first non-null value
 * - [eq][org.maplibre.compose.style.expressions.dsl.eq], [neq][org.maplibre.compose.style.expressions.dsl.neq],
 *   [gt][org.maplibre.compose.style.expressions.dsl.gt],
 *   [gte][org.maplibre.compose.style.expressions.dsl.gte],
 *   [lt][org.maplibre.compose.style.expressions.dsl.lt],
 *   [lte][org.maplibre.compose.style.expressions.dsl.lte] - infix comparison (`=`,`≠`,`>`,`≥`,`<`,
 *   `≤`)
 * - [not][org.maplibre.compose.style.expressions.dsl.not], [all][org.maplibre.compose.style.expressions.dsl.all],
 *   [any][org.maplibre.compose.style.expressions.dsl.any] - boolean operators, also available as
 *   infix [and][org.maplibre.compose.style.expressions.dsl.and],
 *   [or][org.maplibre.compose.style.expressions.dsl.or]
 *
 * ### Ramps, scales, curves
 * - [step][org.maplibre.compose.style.expressions.dsl.step] - produce stepped results
 * - [interpolate][org.maplibre.compose.style.expressions.dsl.interpolate] - produce interpolation
 * - [interpolateHcl][org.maplibre.compose.style.expressions.dsl.interpolateHcl] - produce
 *   interpolation in HCL color space
 * - [interpolateLab][org.maplibre.compose.style.expressions.dsl.interpolateLab] - produce
 *   interpolation in CIELAB color space
 *
 * ### Math
 * - [+][org.maplibre.compose.style.expressions.dsl.plus], [-][org.maplibre.compose.style.expressions.dsl.minus],
 *   [*][org.maplibre.compose.style.expressions.dsl.times],
 *   [/][org.maplibre.compose.style.expressions.dsl.div],
 *   [%][org.maplibre.compose.style.expressions.dsl.rem],
 *   [pow][org.maplibre.compose.style.expressions.dsl.pow],
 *   [sqrt][org.maplibre.compose.style.expressions.dsl.sqrt] - algebraic operations
 * - [log10][org.maplibre.compose.style.expressions.dsl.log10], [log2][org.maplibre.compose.style.expressions.dsl.log2],
 *   [ln][org.maplibre.compose.style.expressions.dsl.ln] - logarithmic functions
 * - [sin][org.maplibre.compose.style.expressions.dsl.sin], [cos][org.maplibre.compose.style.expressions.dsl.cos],
 *   [tan][org.maplibre.compose.style.expressions.dsl.tan],
 *   [asin][org.maplibre.compose.style.expressions.dsl.asin],
 *   [acos][org.maplibre.compose.style.expressions.dsl.acos],
 *   [atan][org.maplibre.compose.style.expressions.dsl.atan] - trigonometric functions
 * - [floor][org.maplibre.compose.style.expressions.dsl.floor], [ceil][org.maplibre.compose.style.expressions.dsl.ceil],
 *   [round][org.maplibre.compose.style.expressions.dsl.round],
 *   [abs][org.maplibre.compose.style.expressions.dsl.round] - coercing numbers
 * - [min][org.maplibre.compose.style.expressions.dsl.min], [max][org.maplibre.compose.style.expressions.dsl.max],
 *   [round][org.maplibre.compose.style.expressions.dsl.round] - rounding integers
 * - [LN_2][org.maplibre.compose.style.expressions.dsl.LN_2], [PI][org.maplibre.compose.style.expressions.dsl.PI],
 *   [E][org.maplibre.compose.style.expressions.dsl.E] - constants
 *
 * ### Inputs, feature data
 * - [zoom][org.maplibre.compose.style.expressions.dsl.zoom] - get current zoom level
 * - [heatmapDensity][org.maplibre.compose.style.expressions.dsl.heatmapDensity] - get heatmap
 *   density
 * - `feature.`[get][org.maplibre.compose.style.expressions.dsl.Feature.get] - get feature attribute
 * - `feature.`[has][org.maplibre.compose.style.expressions.dsl.Feature.has] - check presence of
 *   feature attribute
 * - `feature.`[properties][org.maplibre.compose.style.expressions.dsl.Feature.properties] - get all
 *   feature attributes
 * - `feature.`[state][org.maplibre.compose.style.expressions.dsl.Feature.state] - get property from
 *   feature state
 * - `feature.`[geometryType][org.maplibre.compose.style.expressions.dsl.Feature.geometryType] - get
 *   feature's geometry type
 * - `feature.`[id][org.maplibre.compose.style.expressions.dsl.Feature.id] - get feature id
 * - `feature.`[lineProgress][org.maplibre.compose.style.expressions.dsl.Feature.lineProgress] -
 *   progress along a gradient line
 * - `feature.`[accumulated][org.maplibre.compose.style.expressions.dsl.Feature.accumulated] - value
 *   of accumulated cluster property so far
 * - `feature.`[within][org.maplibre.compose.style.expressions.dsl.Feature.within] - check whether
 *   feature is within geometry
 * - `feature.`[distance][org.maplibre.compose.style.expressions.dsl.Feature.distance] - distance of
 *   feature to geometry
 *
 * ### Collections
 * - `Expression<ListValue<T>>.`[get][org.maplibre.compose.style.expressions.dsl.get] - get value at
 *   index
 * - `Expression<ListValue<T>>.`[contains][org.maplibre.compose.style.expressions.dsl.contains] -
 *   check whether list contains value
 * - `Expression<ListValue<T>>.`[indexOf][org.maplibre.compose.style.expressions.dsl.indexOf] -
 *   check where the list contains value
 * - `Expression<ListValue<T>>.`[slice][org.maplibre.compose.style.expressions.dsl.slice] - return a
 *   sub-list
 * - `Expression<ListValue<T>>.`[length][org.maplibre.compose.style.expressions.dsl.length] - list
 *   length
 * - `Expression<MapValue<T>>.`[get][org.maplibre.compose.style.expressions.dsl.get] - get value
 * - `Expression<MapValue<T>>.`[has][org.maplibre.compose.style.expressions.dsl.has] - check
 *   presence of key
 *
 * ### Strings
 * - `Expression<StringValue>.`[contains][org.maplibre.compose.style.expressions.dsl.contains] -
 *   check if string contains another
 * - `Expression<StringValue>.`[indexOf][org.maplibre.compose.style.expressions.dsl.indexOf] - check
 *   where string contains another
 * - `Expression<StringValue>.`[substring][org.maplibre.compose.style.expressions.dsl.substring] -
 *   return a sub-string
 * - `Expression<StringValue>.`[length][org.maplibre.compose.style.expressions.dsl.length] - string
 *   length
 * - `Expression<StringValue>.` [isScriptSupported][org.maplibre.compose.style.expressions.dsl.isScriptSupported] -
 *   whether string is expected to render correctly
 * - `Expression<StringValue>.`[uppercase][org.maplibre.compose.style.expressions.dsl.uppercase] -
 *   uppercase the string
 * - `Expression<StringValue>.`[lowercase][org.maplibre.compose.style.expressions.dsl.lowercase] -
 *   lowercase the string
 * - `Expression<StringValue>.`[+][org.maplibre.compose.style.expressions.dsl.plus] - concatenate
 *   the string
 * - [resolvedLocale][org.maplibre.compose.style.expressions.dsl.resolvedLocale] - return locale
 *
 * ### Format
 * - [format][org.maplibre.compose.style.expressions.dsl.format] - format text with
 *   [span][org.maplibre.compose.style.expressions.dsl.span]s of different text styling
 *
 * ### Color
 * - [rgbColor][org.maplibre.compose.style.expressions.dsl.rgbColor] - create color from components
 * - `Expression<ColorValue>.` [toRgbaComponents][org.maplibre.compose.style.expressions.dsl.toRgbaComponents] -
 *   deconstruct color into components
 *
 * ### Image
 * - [image][org.maplibre.compose.style.expressions.dsl.image] - image for use in `iconImage`
 *
 * ### Variable binding
 * - [withVariable][org.maplibre.compose.style.expressions.dsl.withVariable] - define variable
 *   within expression
 */
public sealed interface Expression<out T : ExpressionValue> {
  /** Transform this expression into the equivalent [CompiledExpression]. */
  public fun compile(context: ExpressionContext): CompiledExpression<T>

  public fun visit(block: (Expression<*>) -> Unit)

  @Suppress("UNCHECKED_CAST")
  public fun <X : ExpressionValue> cast(): Expression<X> = this as Expression<X>
}
