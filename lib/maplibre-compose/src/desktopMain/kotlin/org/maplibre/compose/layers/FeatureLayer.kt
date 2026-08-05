package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.sources.Source

internal actual sealed class FeatureLayer(id: String, actual val source: Source) : Layer(id) {

  /**
   * Exists only to match the common `expect` constructor; throws, because a layer with no id cannot
   * be added to a style. Subclasses use the id-carrying constructor above.
   */
  protected actual constructor(source: Source) : this("", source) {
    error("A desktop feature layer must be constructed with its layer id")
  }

  override val sourceId: String
    get() = source.id

  override val sourceDescriptor: Source
    get() = source

  actual abstract var sourceLayer: String

  actual abstract fun setFilter(filter: CompiledExpression<BooleanValue>)

  /**
   * Writes the `source-layer` key for the [sourceLayer] override each subclass declares. It is a
   * root key, not a paint or layout property, so it must reach the JSON that creates the layer;
   * mbgl's `Layer::setProperty` still accepts it on a live layer through its common-key fallback.
   */
  protected fun setSourceLayerProperty(sourceLayer: String) {
    setRootProperty("source-layer", JsonPrimitive(sourceLayer))
  }
}
