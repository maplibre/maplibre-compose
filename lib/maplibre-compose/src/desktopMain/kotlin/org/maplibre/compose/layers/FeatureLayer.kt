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

  override val sourceDescriptor: Source
    get() = source

  actual abstract var sourceLayer: String

  actual abstract fun setFilter(filter: CompiledExpression<BooleanValue>)

  /**
   * Writes the `source-layer` key for the [sourceLayer] override each subclass declares.
   *
   * Both halves matter. It is a root key rather than a paint or layout property, so it has to reach
   * the JSON that creates the layer: a layer over a vector source that is missing it selects no
   * features and draws nothing. And it does reach a live layer through `setLayerProperty` despite
   * not being a paint or layout property, because mbgl's `Layer::setProperty` falls back to the
   * common keys — `visibility`, `minzoom`, `maxzoom`, `filter`, `source-layer`, `source` — once the
   * layer's own generated setter declines the name.
   */
  protected fun setSourceLayerProperty(sourceLayer: String) {
    setRootProperty("source-layer", JsonPrimitive(sourceLayer))
  }
}
