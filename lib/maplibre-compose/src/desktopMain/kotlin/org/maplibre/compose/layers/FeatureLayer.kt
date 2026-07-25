package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.sources.Source

internal actual sealed class FeatureLayer(id: String, actual val source: Source) : Layer(id) {

  /**
   * Exists only to match the common `expect` constructor.
   *
   * A layer with no id cannot be added to a style, so this throws rather than producing one that
   * silently fails to render. Subclasses use the id-carrying constructor above.
   */
  protected actual constructor(source: Source) : this("", source) {
    error("A desktop feature layer must be constructed with its layer id")
  }

  override val sourceId: String
    get() = source.id

  actual abstract var sourceLayer: String

  actual abstract fun setFilter(filter: CompiledExpression<BooleanValue>)

  /**
   * Writes the `source-layer` key for the [sourceLayer] override each subclass declares.
   *
   * This is a root key rather than a paint or layout property, so it has to reach the JSON that
   * creates the layer: a layer over a vector source that is missing it selects no features and
   * draws nothing.
   */
  protected fun setSourceLayerProperty(sourceLayer: String) {
    setRootProperty("source-layer", JsonPrimitive(sourceLayer))
  }
}
