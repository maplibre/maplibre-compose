package org.maplibre.compose.style.layer

import org.maplibre.compose.style.expressions.ast.CompiledExpression
import org.maplibre.compose.style.expressions.value.BooleanValue
import org.maplibre.compose.style.source.Source

internal expect sealed class FeatureLayer(source: Source) : Layer {
  val source: Source
  abstract var sourceLayer: String

  abstract fun setFilter(filter: CompiledExpression<BooleanValue>)
}
