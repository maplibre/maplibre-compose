package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.sources.Source

internal actual sealed class FeatureLayer(id: String, actual val source: Source) : Layer(id) {

  override val sourceId: String
    get() = source.id

  override val sourceDescriptor: Source
    get() = source

  actual abstract var sourceLayer: String

  actual abstract fun setFilter(filter: CompiledExpression<BooleanValue>)

  /**
   * `source-layer` is a root key, not paint or layout, so it must be in the layer-creation JSON;
   * mbgl's `Layer::setProperty` still accepts it on a live layer through its common-key fallback.
   */
  protected fun setSourceLayerProperty(sourceLayer: String) {
    setRootProperty("source-layer", JsonPrimitive(sourceLayer))
  }
}
