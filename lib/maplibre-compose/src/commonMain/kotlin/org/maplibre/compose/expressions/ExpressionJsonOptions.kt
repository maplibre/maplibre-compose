package org.maplibre.compose.expressions

import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.map.MapOptionsDsl

/**
 * Context inputs for [toStyleJson]. Each scale is required only when the expression uses that unit.
 *
 * Factors multiply input values into the units expected by the destination style property. For
 * example, exporting a text size in EM to DP needs the text size in DP as [emScale]; exporting SP
 * to EM needs the font scale divided by that text size as [spScale]. Scale expressions may depend
 * on feature data. Ordinary DP sizes and offsets keep their DP values and need no scale.
 *
 * @property emScale Factor for EM text values and offsets, or `null` if unavailable.
 * @property spScale Factor for SP text values and offsets, or `null` if unavailable.
 * @property dpScale Factor for DP text offsets, or `null` if unavailable.
 * @property imageResolver Supplies style image IDs for bitmap and painter references, or `null` if
 *   unavailable. Called synchronously on the exporting thread, once per distinct reference in each
 *   export. Separate exports resolve again. Exceptions from the resolver propagate.
 */
public data class ExpressionJsonOptions
private constructor(
  public val emScale: Expression<FloatValue>?,
  public val spScale: Expression<FloatValue>?,
  public val dpScale: Expression<FloatValue>?,
  public val imageResolver: ExpressionImageResolver?,
) {
  private constructor(
    builder: Builder
  ) : this(
    builder.emScale,
    builder.spScale,
    builder.dpScale,
    builder.imageResolver,
  )

  /** Edits [from]; omitted inputs inherit. */
  public constructor(
    from: ExpressionJsonOptions = Standard,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  @MapOptionsDsl
  public class Builder internal constructor(from: ExpressionJsonOptions?) {
    /** See [ExpressionJsonOptions.emScale]. */
    public var emScale: Expression<FloatValue>? = from?.emScale
    /** See [ExpressionJsonOptions.spScale]. */
    public var spScale: Expression<FloatValue>? = from?.spScale
    /** See [ExpressionJsonOptions.dpScale]. */
    public var dpScale: Expression<FloatValue>? = from?.dpScale
    /** See [ExpressionJsonOptions.imageResolver]. */
    public var imageResolver: ExpressionImageResolver? = from?.imageResolver
  }

  public companion object {
    /** No text-unit scales or image resolver. Suitable for ordinary style-spec expressions. */
    public val Standard: ExpressionJsonOptions = ExpressionJsonOptions(Builder(null))
  }
}
