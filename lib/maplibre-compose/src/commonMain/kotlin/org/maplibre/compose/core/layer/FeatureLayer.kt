package org.maplibre.compose.core.layer

import org.maplibre.compose.core.source.Source
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue

internal expect sealed class FeatureLayer(source: Source) : Layer {
  val source: Source
  abstract var sourceLayer: String

  abstract fun setFilter(filter: CompiledExpression<BooleanValue>)
}
