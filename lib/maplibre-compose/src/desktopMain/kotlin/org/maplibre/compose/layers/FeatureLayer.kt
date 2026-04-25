package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toJsonString

internal actual sealed class FeatureLayer actual constructor(actual val source: Source) : Layer() {

  private var _sourceLayer: String = ""
  private var _filter: String? = null

  actual abstract var sourceLayer: String

  actual abstract fun setFilter(filter: CompiledExpression<BooleanValue>)

  protected fun updateSourceLayer(value: String) {
    _sourceLayer = value
    style?.updateLayer(this)
  }

  protected fun updateFilter(filter: CompiledExpression<BooleanValue>) {
    _filter = filter.toJsonString()
    style?.updateLayer(this)
  }

  final override fun sourceId(): String = source.id

  final override fun sourceLayerString(): String = _sourceLayer

  final override fun filterJson(): String? = _filter
}
