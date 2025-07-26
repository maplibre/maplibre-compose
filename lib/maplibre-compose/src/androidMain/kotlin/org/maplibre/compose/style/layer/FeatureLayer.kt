package org.maplibre.compose.style.layer

import org.maplibre.compose.style.expressions.ast.CompiledExpression
import org.maplibre.compose.style.expressions.value.BooleanValue
import org.maplibre.compose.style.source.Source

internal actual sealed class FeatureLayer actual constructor(actual val source: Source) : Layer() {
  actual abstract var sourceLayer: String

  actual abstract fun setFilter(filter: CompiledExpression<BooleanValue>)
}
