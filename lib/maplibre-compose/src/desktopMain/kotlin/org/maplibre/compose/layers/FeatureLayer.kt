package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.sources.Source
import org.maplibre.kmp.native.style.layers.FeatureLayer as MLNFeatureLayer

internal actual sealed class FeatureLayer actual constructor(actual val source: Source) : Layer() {
  abstract override val impl: MLNFeatureLayer

  actual abstract var sourceLayer: String

  actual abstract fun setFilter(filter: CompiledExpression<BooleanValue>)
}
