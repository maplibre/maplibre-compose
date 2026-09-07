package org.maplibre.compose.expressions.kotlin

import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.ExpressionValue

/**
 * Builds a MapLibre style expression from ordinary Kotlin.
 *
 * The compiler plugin rewrites the [block] into the [Expression] AST. The block type-checks as
 * normal Kotlin: `if`/`when`, operators, `kotlin.math`, and string/list methods are evaluated by
 * the map, not during composition.
 *
 * [T] is inferred from the layer property (`color = expr { Color.Red }` is
 * `Expression<ColorValue>`). Without the plugin this function throws.
 */
public fun <T : ExpressionValue> expr(block: ExprScope.() -> Any?): Expression<T> = pluginRequired()

internal fun pluginRequired(): Nothing =
  error(
    "expr { } requires the MapLibre Compose compiler plugin. " +
      "Apply applyMapLibreExprCompilerPlugin() in the consuming Gradle project."
  )
