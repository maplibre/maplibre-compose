package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.ImageResizeOptions

/**
 * A style that tolerates being used after it has been replaced: during a style switch the outgoing
 * style's content is briefly still composed while anchors and sources already name the incoming
 * style's layers. After [unload], writes no-op and [LayerManager] skips validation and pending
 * layer changes (#269).
 *
 * Every platform must unload the outgoing style when a new one is *requested* (adapters report
 * `onStyleChanged(map, null)` from `setBaseStyle`), and must do so before the content
 * subcomposition applies its changes — `AndroidView`'s `update` block is early enough, a
 * `LaunchedEffect` is not.
 */
internal class SafeStyle(private val delegate: Style, private val logger: Logger? = null) : Style {
  internal var isUnloaded = false

  internal fun unload() {
    isUnloaded = true
  }

  private fun warnIfUnloaded(methodName: String) {
    if (isUnloaded) {
      logger?.w { "Ignoring $methodName on an unloaded style" }
    }
  }

  override fun addImage(
    id: String,
    image: ImageBitmap,
    sdf: Boolean,
    resizeOptions: ImageResizeOptions?,
  ) {
    warnIfUnloaded("addImage")
    if (!isUnloaded) delegate.addImage(id, image, sdf, resizeOptions)
  }

  override fun removeImage(id: String) {
    warnIfUnloaded("removeImage")
    if (!isUnloaded) delegate.removeImage(id)
  }

  override fun getSource(id: String): Source? {
    warnIfUnloaded("getSource")
    return if (!isUnloaded) delegate.getSource(id) else null
  }

  override fun getSources(): List<Source> {
    warnIfUnloaded("getSources")
    return if (!isUnloaded) delegate.getSources() else emptyList()
  }

  override fun addSource(source: Source) {
    warnIfUnloaded("addSource")
    if (!isUnloaded) delegate.addSource(source)
  }

  override fun removeSource(source: Source) {
    warnIfUnloaded("removeSource")
    if (!isUnloaded) delegate.removeSource(source)
  }

  override fun getLayer(id: String): Layer? {
    warnIfUnloaded("getLayer")
    return if (!isUnloaded) delegate.getLayer(id) else null
  }

  override fun getLayers(): List<Layer> {
    warnIfUnloaded("getLayers")
    return if (!isUnloaded) delegate.getLayers() else emptyList()
  }

  override fun addLayer(layer: Layer) {
    warnIfUnloaded("addLayer")
    if (!isUnloaded) delegate.addLayer(layer)
  }

  override fun addLayerAbove(id: String, layer: Layer) {
    warnIfUnloaded("addLayerAbove")
    if (!isUnloaded) delegate.addLayerAbove(id, layer)
  }

  override fun addLayerBelow(id: String, layer: Layer) {
    warnIfUnloaded("addLayerBelow")
    if (!isUnloaded) delegate.addLayerBelow(id, layer)
  }

  override fun addLayerAt(index: Int, layer: Layer) {
    warnIfUnloaded("addLayerAt")
    if (!isUnloaded) delegate.addLayerAt(index, layer)
  }

  override fun removeLayer(layer: Layer) {
    warnIfUnloaded("removeLayer")
    if (!isUnloaded) delegate.removeLayer(layer)
  }
}
