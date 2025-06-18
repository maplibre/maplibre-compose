package dev.sargunv.maplibrecompose.expressions.ast

import dev.sargunv.maplibrecompose.expressions.ExpressionContext
import dev.sargunv.maplibrecompose.expressions.value.ExpressionValue

/**
 * An [Expression] that evaluates to a value of type [T].
 *
 * The functions to create expressions are defined in the
 * [`dev.sargunv.maplibrecompose.expressions.dsl`](https://maplibre.org/maplibre-compose/api/lib/maplibre-compose-expressions/dev.sargunv.maplibrecompose.expressions.dsl/index.html)
 * package.
 *
 * Most functions are named the same as in the
 * [MapLibre style specification](https://maplibre.org/maplibre-style-spec/expressions/), a few have
 * been renamed to be Kotlin-idiomatic or made into extension functions (to an [Expression]).
 *
 * # Function overview
 *
 * ### Literals
 * - [const][dev.sargunv.maplibrecompose.expressions.dsl.const] - literal expression
 * - [nil][dev.sargunv.maplibrecompose.expressions.dsl.nil] - literal `null` expression
 *
 * ### Decision
 * - [switch][dev.sargunv.maplibrecompose.expressions.dsl.switch] - if-else / switch-case
 * - [coalesce][dev.sargunv.maplibrecompose.expressions.dsl.coalesce] - get first non-null value
 * - [eq][dev.sargunv.maplibrecompose.expressions.dsl.eq], [neq][dev.sargunv.maplibrecompose.expressions.dsl.neq],
 *   [gt][dev.sargunv.maplibrecompose.expressions.dsl.gt],
 *   [gte][dev.sargunv.maplibrecompose.expressions.dsl.gte],
 *   [lt][dev.sargunv.maplibrecompose.expressions.dsl.lt],
 *   [lte][dev.sargunv.maplibrecompose.expressions.dsl.lte] - infix comparison (`=`,`≠`,`>`,`≥`,`<`,
 *   `≤`)
 * - [not][dev.sargunv.maplibrecompose.expressions.dsl.not], [all][dev.sargunv.maplibrecompose.expressions.dsl.all],
 *   [any][dev.sargunv.maplibrecompose.expressions.dsl.any] - boolean operators, also available as
 *   infix [and][dev.sargunv.maplibrecompose.expressions.dsl.and],
 *   [or][dev.sargunv.maplibrecompose.expressions.dsl.or]
 *
 * ### Ramps, scales, curves
 * - [step][dev.sargunv.maplibrecompose.expressions.dsl.step] - produce stepped results
 * - [interpolate][dev.sargunv.maplibrecompose.expressions.dsl.interpolate] - produce interpolation
 * - [interpolateHcl][dev.sargunv.maplibrecompose.expressions.dsl.interpolateHcl] - produce
 *   interpolation in HCL color space
 * - [interpolateLab][dev.sargunv.maplibrecompose.expressions.dsl.interpolateLab] - produce
 *   interpolation in CIELAB color space
 *
 * ### Math
 * - [+][dev.sargunv.maplibrecompose.expressions.dsl.plus], [-][dev.sargunv.maplibrecompose.expressions.dsl.minus],
 *   [*][dev.sargunv.maplibrecompose.expressions.dsl.times],
 *   [/][dev.sargunv.maplibrecompose.expressions.dsl.div],
 *   [%][dev.sargunv.maplibrecompose.expressions.dsl.rem],
 *   [pow][dev.sargunv.maplibrecompose.expressions.dsl.pow],
 *   [sqrt][dev.sargunv.maplibrecompose.expressions.dsl.sqrt] - algebraic operations
 * - [log10][dev.sargunv.maplibrecompose.expressions.dsl.log10], [log2][dev.sargunv.maplibrecompose.expressions.dsl.log2],
 *   [ln][dev.sargunv.maplibrecompose.expressions.dsl.ln] - logarithmic functions
 * - [sin][dev.sargunv.maplibrecompose.expressions.dsl.sin], [cos][dev.sargunv.maplibrecompose.expressions.dsl.cos],
 *   [tan][dev.sargunv.maplibrecompose.expressions.dsl.tan],
 *   [asin][dev.sargunv.maplibrecompose.expressions.dsl.asin],
 *   [acos][dev.sargunv.maplibrecompose.expressions.dsl.acos],
 *   [atan][dev.sargunv.maplibrecompose.expressions.dsl.atan] - trigonometric functions
 * - [floor][dev.sargunv.maplibrecompose.expressions.dsl.floor], [ceil][dev.sargunv.maplibrecompose.expressions.dsl.ceil],
 *   [round][dev.sargunv.maplibrecompose.expressions.dsl.round],
 *   [abs][dev.sargunv.maplibrecompose.expressions.dsl.round] - coercing numbers
 * - [min][dev.sargunv.maplibrecompose.expressions.dsl.min], [max][dev.sargunv.maplibrecompose.expressions.dsl.max],
 *   [round][dev.sargunv.maplibrecompose.expressions.dsl.round] - rounding integers
 * - [LN_2][dev.sargunv.maplibrecompose.expressions.dsl.LN_2], [PI][dev.sargunv.maplibrecompose.expressions.dsl.PI],
 *   [E][dev.sargunv.maplibrecompose.expressions.dsl.E] - constants
 *
 * ### Inputs, feature data
 * - [zoom][dev.sargunv.maplibrecompose.expressions.dsl.zoom] - get current zoom level
 * - [heatmapDensity][dev.sargunv.maplibrecompose.expressions.dsl.heatmapDensity] - get heatmap
 *   density
 * - `feature.`[get][dev.sargunv.maplibrecompose.expressions.dsl.Feature.get] - get feature
 *   attribute
 * - `feature.`[has][dev.sargunv.maplibrecompose.expressions.dsl.Feature.has] - check presence of
 *   feature attribute
 * - `feature.`[properties][dev.sargunv.maplibrecompose.expressions.dsl.Feature.properties] - get
 *   all feature attributes
 * - `feature.`[state][dev.sargunv.maplibrecompose.expressions.dsl.Feature.state] - get property
 *   from feature state
 * - `feature.`[geometryType][dev.sargunv.maplibrecompose.expressions.dsl.Feature.geometryType] -
 *   get feature's geometry type
 * - `feature.`[id][dev.sargunv.maplibrecompose.expressions.dsl.Feature.id] - get feature id
 * - `feature.`[lineProgress][dev.sargunv.maplibrecompose.expressions.dsl.Feature.lineProgress] -
 *   progress along a gradient line
 * - `feature.`[accumulated][dev.sargunv.maplibrecompose.expressions.dsl.Feature.accumulated] -
 *   value of accumulated cluster property so far
 * - `feature.`[within][dev.sargunv.maplibrecompose.expressions.dsl.Feature.within] - check whether
 *   feature is within geometry
 * - `feature.`[distance][dev.sargunv.maplibrecompose.expressions.dsl.Feature.distance] - distance
 *   of feature to geometry
 *
 * ### Collections
 * - `Expression<ListValue<T>>.`[get][dev.sargunv.maplibrecompose.expressions.dsl.get] - get value
 *   at index
 * - `Expression<ListValue<T>>.`[contains][dev.sargunv.maplibrecompose.expressions.dsl.contains] -
 *   check whether list contains value
 * - `Expression<ListValue<T>>.`[indexOf][dev.sargunv.maplibrecompose.expressions.dsl.indexOf] -
 *   check where the list contains value
 * - `Expression<ListValue<T>>.`[slice][dev.sargunv.maplibrecompose.expressions.dsl.slice] - return
 *   a sub-list
 * - `Expression<ListValue<T>>.`[length][dev.sargunv.maplibrecompose.expressions.dsl.length] - list
 *   length
 * - `Expression<MapValue<T>>.`[get][dev.sargunv.maplibrecompose.expressions.dsl.get] - get value
 * - `Expression<MapValue<T>>.`[has][dev.sargunv.maplibrecompose.expressions.dsl.has] - check
 *   presence of key
 *
 * ### Strings
 * - `Expression<StringValue>.`[contains][dev.sargunv.maplibrecompose.expressions.dsl.contains] -
 *   check if string contains another
 * - `Expression<StringValue>.`[indexOf][dev.sargunv.maplibrecompose.expressions.dsl.indexOf] -
 *   check where string contains another
 * - `Expression<StringValue>.`[substring][dev.sargunv.maplibrecompose.expressions.dsl.substring] -
 *   return a sub-string
 * - `Expression<StringValue>.`[length][dev.sargunv.maplibrecompose.expressions.dsl.length] - string
 *   length
 * - `Expression<StringValue>.` [isScriptSupported][dev.sargunv.maplibrecompose.expressions.dsl.isScriptSupported] -
 *   whether string is expected to render correctly
 * - `Expression<StringValue>.`[uppercase][dev.sargunv.maplibrecompose.expressions.dsl.uppercase] -
 *   uppercase the string
 * - `Expression<StringValue>.`[lowercase][dev.sargunv.maplibrecompose.expressions.dsl.lowercase] -
 *   lowercase the string
 * - `Expression<StringValue>.`[+][dev.sargunv.maplibrecompose.expressions.dsl.plus] - concatenate
 *   the string
 * - [resolvedLocale][dev.sargunv.maplibrecompose.expressions.dsl.resolvedLocale] - return locale
 *
 * ### Format
 * - [format][dev.sargunv.maplibrecompose.expressions.dsl.format] - format text with
 *   [span][dev.sargunv.maplibrecompose.expressions.dsl.span]s of different text styling
 *
 * ### Color
 * - [rgbColor][dev.sargunv.maplibrecompose.expressions.dsl.rgbColor] - create color from components
 * - `Expression<ColorValue>.` [toRgbaComponents][dev.sargunv.maplibrecompose.expressions.dsl.toRgbaComponents] -
 *   deconstruct color into components
 *
 * ### Image
 * - [image][dev.sargunv.maplibrecompose.expressions.dsl.image] - image for use in `iconImage`
 *
 * ### Variable binding
 * - [withVariable][dev.sargunv.maplibrecompose.expressions.dsl.withVariable] - define variable
 *   within expression
 */
public sealed interface Expression<out T : ExpressionValue> {
  /** Transform this expression into the equivalent [CompiledExpression]. */
  public fun compile(context: ExpressionContext): CompiledExpression<T>

  public fun visit(block: (Expression<*>) -> Unit)

  @Suppress("UNCHECKED_CAST")
  public fun <X : ExpressionValue> cast(): Expression<X> = this as Expression<X>
}
