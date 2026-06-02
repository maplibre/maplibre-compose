package org.maplibre.compose.style

public class BaseStyleLayers internal constructor(private val styleNode: StyleNode) {
  /** Ids of base-style's layers, in render order. */
  public val ids: List<String>
    get() = if (styleNode.style.isUnloaded) emptyList() else styleNode.layerManager.baseLayerIds

  /**
   * A handle to the base-style layer with the given [id], or `null` if there is no such base layer
   * — either it doesn't exist, or it's a layer added through a composable.
   */
  public operator fun get(id: String): BaseStyleLayer? {
    val style = styleNode.style
    val layerManager = styleNode.layerManager
    return when {
      style.isUnloaded -> {
        styleNode.logger?.w { "No style is currently loaded; cannot get base-style layer $id." }
        null
      }
      layerManager.isBaseLayer(id) -> BaseStyleLayer(styleNode, id)
      layerManager.isComposableLayer(id) -> {
        styleNode.logger?.w { "Layer $id is not a base-style layer." }
        null
      }
      else -> {
        styleNode.logger?.w { "Layer $id doesn't exist in base-style." }
        null
      }
    }
  }
}

/** An imperative handle to a base-style layer. */
public class BaseStyleLayer
internal constructor(private val styleNode: StyleNode, public val id: String) {
  public var visible: Boolean
    get() = styleNode.style.getLayer(id)?.visible ?: true
    set(value) {
      styleNode.style.getLayer(id)?.visible = value
    }

  public var minZoom: Float
    get() = styleNode.style.getLayer(id)?.minZoom ?: 0.0f
    set(value) {
      styleNode.style.getLayer(id)?.minZoom = value
    }

  public var maxZoom: Float
    get() = styleNode.style.getLayer(id)?.maxZoom ?: 24.0f
    set(value) {
      styleNode.style.getLayer(id)?.maxZoom = value
    }
}
