package org.maplibre.compose.expressions

import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.compile
import org.maplibre.compose.expressions.internal.ExportExpressionContext
import org.maplibre.compose.style.internal.StyleValue

/**
 * Exports this expression as compact MapLibre style-spec JSON, without a map or composition.
 *
 * Uses the same expression encoding as layer properties. [options] supplies the context needed by
 * text units and bitmap or painter references. Named images need no resolver. Images are neither
 * drawn nor registered; a caller using the result in a style must register the resolved IDs.
 *
 * This compiles and encodes the expression; it does not validate operators against an engine or
 * evaluate feature data. Numeric formatting and object key order may differ across platforms and
 * releases; compare parsed JSON when testing expressions.
 *
 * @throws IllegalArgumentException If a required scale or image resolver is missing, an image
 *   cannot be resolved, or an expression number is not finite.
 * @see ExpressionJsonOptions
 */
public fun Expression<*>.toStyleJson(
  options: ExpressionJsonOptions = ExpressionJsonOptions.Standard
): String = StyleValue.Expression(compile(ExportExpressionContext(options))).encoded.toJson()
