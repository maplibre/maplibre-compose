package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ExpressionValue

/**
 * An [Expression] that evaluates to a value of type [T].
 *
 * The functions to create expressions are defined in the
 * [`org.maplibre.compose.expressions.dsl`](https://maplibre.org/maplibre-compose/api/lib/maplibre-compose/org.maplibre.compose.expressions.dsl/index.html)
 * package.
 *
 * Most functions are named the same as in the
 * [MapLibre style specification](https://maplibre.org/maplibre-style-spec/expressions/), a few have
 * been renamed to be Kotlin-idiomatic or made into extension functions (to an [Expression]).
 *
 * # Function overview
 *
 * ### Literals
 * - [const][org.maplibre.compose.expressions.dsl.const] - literal expression
 * - [nil][org.maplibre.compose.expressions.dsl.nil] - literal `null` expression
 *
 * ### Decision
 * - [switch][org.maplibre.compose.expressions.dsl.switch] - if-else / switch-case
 * - [coalesce][org.maplibre.compose.expressions.dsl.coalesce] - get first non-null value
 * - [eq][org.maplibre.compose.expressions.dsl.eq], [neq][org.maplibre.compose.expressions.dsl.neq],
 *   [gt][org.maplibre.compose.expressions.dsl.gt], [gte][org.maplibre.compose.expressions.dsl.gte],
 *   [lt][org.maplibre.compose.expressions.dsl.lt],
 *   [lte][org.maplibre.compose.expressions.dsl.lte] - infix comparison (`=`,`≠`,`>`,`≥`,`<`, `≤`)
 * - [not][org.maplibre.compose.expressions.dsl.not], [all][org.maplibre.compose.expressions.dsl.all],
 *   [any][org.maplibre.compose.expressions.dsl.any] - boolean operators, also available as infix
 *   [and][org.maplibre.compose.expressions.dsl.and], [or][org.maplibre.compose.expressions.dsl.or]
 *
 * ### Ramps, scales, curves
 * - [step][org.maplibre.compose.expressions.dsl.step] - produce stepped results
 * - [interpolate][org.maplibre.compose.expressions.dsl.interpolate] - produce interpolation
 * - [interpolateHcl][org.maplibre.compose.expressions.dsl.interpolateHcl] - produce interpolation
 *   in HCL color space
 * - [interpolateLab][org.maplibre.compose.expressions.dsl.interpolateLab] - produce interpolation
 *   in CIELAB color space
 *
 * ### Math
 * - [+][org.maplibre.compose.expressions.dsl.plus], [-][org.maplibre.compose.expressions.dsl.minus],
 *   [*][org.maplibre.compose.expressions.dsl.times], [/][org.maplibre.compose.expressions.dsl.div],
 *   [%][org.maplibre.compose.expressions.dsl.rem], [pow][org.maplibre.compose.expressions.dsl.pow],
 *   [sqrt][org.maplibre.compose.expressions.dsl.sqrt] - algebraic operations
 * - [log10][org.maplibre.compose.expressions.dsl.log10], [log2][org.maplibre.compose.expressions.dsl.log2],
 *   [ln][org.maplibre.compose.expressions.dsl.ln] - logarithmic functions
 * - [sin][org.maplibre.compose.expressions.dsl.sin], [cos][org.maplibre.compose.expressions.dsl.cos],
 *   [tan][org.maplibre.compose.expressions.dsl.tan],
 *   [asin][org.maplibre.compose.expressions.dsl.asin],
 *   [acos][org.maplibre.compose.expressions.dsl.acos],
 *   [atan][org.maplibre.compose.expressions.dsl.atan] - trigonometric functions
 * - [floor][org.maplibre.compose.expressions.dsl.floor], [ceil][org.maplibre.compose.expressions.dsl.ceil],
 *   [round][org.maplibre.compose.expressions.dsl.round],
 *   [abs][org.maplibre.compose.expressions.dsl.round] - coercing numbers
 * - [min][org.maplibre.compose.expressions.dsl.min], [max][org.maplibre.compose.expressions.dsl.max],
 *   [round][org.maplibre.compose.expressions.dsl.round] - rounding integers
 * - [LN_2][org.maplibre.compose.expressions.dsl.LN_2], [PI][org.maplibre.compose.expressions.dsl.PI],
 *   [E][org.maplibre.compose.expressions.dsl.E] - constants
 *
 * ### Inputs, feature data
 * - [zoom][org.maplibre.compose.expressions.dsl.zoom] - get current zoom level
 * - [heatmapDensity][org.maplibre.compose.expressions.dsl.heatmapDensity] - get heatmap density
 * - `feature.`[get][org.maplibre.compose.expressions.dsl.Feature.get] - get feature attribute
 * - `feature.`[has][org.maplibre.compose.expressions.dsl.Feature.has] - check presence of feature
 *   attribute
 * - `feature.`[properties][org.maplibre.compose.expressions.dsl.Feature.properties] - get all
 *   feature attributes
 * - `feature.`[state][org.maplibre.compose.expressions.dsl.Feature.state] - get property from
 *   feature state
 * - `feature.`[geometryType][org.maplibre.compose.expressions.dsl.Feature.geometryType] - get
 *   feature's geometry type
 * - `feature.`[id][org.maplibre.compose.expressions.dsl.Feature.id] - get feature id
 * - `feature.`[lineProgress][org.maplibre.compose.expressions.dsl.Feature.lineProgress] - progress
 *   along a gradient line
 * - `feature.`[accumulated][org.maplibre.compose.expressions.dsl.Feature.accumulated] - value of
 *   accumulated cluster property so far
 * - `feature.`[within][org.maplibre.compose.expressions.dsl.Feature.within] - check whether feature
 *   is within geometry
 * - `feature.`[distance][org.maplibre.compose.expressions.dsl.Feature.distance] - distance of
 *   feature to geometry
 *
 * ### Collections
 * - `Expression<ListValue<T>>.`[get][org.maplibre.compose.expressions.dsl.get] - get value at index
 * - `Expression<ListValue<T>>.`[contains][org.maplibre.compose.expressions.dsl.contains] - check
 *   whether list contains value
 * - `Expression<ListValue<T>>.`[indexOf][org.maplibre.compose.expressions.dsl.indexOf] - check
 *   where the list contains value
 * - `Expression<ListValue<T>>.`[slice][org.maplibre.compose.expressions.dsl.slice] - return a
 *   sub-list
 * - `Expression<ListValue<T>>.`[length][org.maplibre.compose.expressions.dsl.length] - list length
 * - `Expression<MapValue<T>>.`[get][org.maplibre.compose.expressions.dsl.get] - get value
 * - `Expression<MapValue<T>>.`[has][org.maplibre.compose.expressions.dsl.has] - check presence of
 *   key
 *
 * ### Strings
 * - `Expression<StringValue>.`[contains][org.maplibre.compose.expressions.dsl.contains] - check if
 *   string contains another
 * - `Expression<StringValue>.`[indexOf][org.maplibre.compose.expressions.dsl.indexOf] - check where
 *   string contains another
 * - `Expression<StringValue>.`[substring][org.maplibre.compose.expressions.dsl.substring] - return
 *   a sub-string
 * - `Expression<StringValue>.`[length][org.maplibre.compose.expressions.dsl.length] - string length
 * - `Expression<StringValue>.` [isScriptSupported][org.maplibre.compose.expressions.dsl.isScriptSupported] -
 *   whether string is expected to render correctly
 * - `Expression<StringValue>.`[uppercase][org.maplibre.compose.expressions.dsl.uppercase] -
 *   uppercase the string
 * - `Expression<StringValue>.`[lowercase][org.maplibre.compose.expressions.dsl.lowercase] -
 *   lowercase the string
 * - `Expression<StringValue>.`[+][org.maplibre.compose.expressions.dsl.plus] - concatenate the
 *   string
 * - [resolvedLocale][org.maplibre.compose.expressions.dsl.resolvedLocale] - return locale
 *
 * ### Format
 * - [format][org.maplibre.compose.expressions.dsl.format] - format text with
 *   [span][org.maplibre.compose.expressions.dsl.span]s of different text styling
 *
 * ### Color
 * - [rgbColor][org.maplibre.compose.expressions.dsl.rgbColor] - create color from components
 * - `Expression<ColorValue>.` [toRgbaComponents][org.maplibre.compose.expressions.dsl.toRgbaComponents] -
 *   deconstruct color into components
 *
 * ### Image
 * - [image][org.maplibre.compose.expressions.dsl.image] - image for use in `iconImage`
 *
 * ### Variable binding
 * - [withVariable][org.maplibre.compose.expressions.dsl.withVariable] - define variable within
 *   expression
 */
public sealed interface Expression<out T : ExpressionValue> {
  /** Transform this expression into the equivalent [CompiledExpression]. */
  public fun compile(context: ExpressionContext): CompiledExpression<T>

  public fun visit(block: (Expression<*>) -> Unit)

  @Suppress("UNCHECKED_CAST")
  public fun <X : ExpressionValue> cast(): Expression<X> = this as Expression<X>
}
