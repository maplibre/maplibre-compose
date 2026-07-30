package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.ImageResizeOptions

/**
 * A style that tolerates being used after it has been replaced.
 *
 * Switching the base style leaves the outgoing style's content briefly still composed: the map's
 * content is a subcomposition tied to the style that *loaded*, while the anchors and sources in
 * that content come from application state, which has already moved to the incoming style. For the
 * span of one style switch the two disagree, and every write the dying composition makes — removing
 * its layers, re-adding them under the new anchors — is aimed at a style that is gone.
 *
 * [unload] is what makes that survivable rather than fatal. It is not a diagnostic: writes no-op
 * afterwards, and [LayerManager] skips anchor validation entirely against an unloaded style,
 * because an anchor naming a layer of the incoming style cannot be checked against the outgoing
 * one. Added in #269 for exactly this.
 *
 * Two things follow, and both have cost a day each to rediscover:
 * - **Every platform must unload the outgoing style when a new one is *requested*, not when the new
 *   one has loaded.** The adapters do this by reporting `onStyleChanged(map, null)` from
 *   `setBaseStyle`. Desktop did not, and crashed on every style switch.
 * - **It has to happen before the content subcomposition applies its changes.** `AndroidView`'s
 *   `update` block runs inside the parent composition's apply, which is early enough; a
 *   `LaunchedEffect` runs after every composition has applied, which is not. Desktop used the
 *   latter and still crashed after fixing the first point.
 *
 * So the correctness of a style switch rests on Compose's scheduling order, stated nowhere and
 * checked by nothing. That is worth removing rather than documenting further: the underlying
 * problem is that content is bound to the loaded style instead of the requested one, so the window
 * exists at all. Making the content's target style explicit would make composing against a style
 * the application has left unrepresentable, and this class and its escape hatch could go.
 */
internal class SafeStyle(private val delegate: Style) : Style {
  /** See the class docs; skipping anchor validation depends on this, so it is load-bearing. */
  internal var isUnloaded = false

  internal fun unload() {
    isUnloaded = true
  }

  private fun warnIfUnloaded(methodName: String) {
    if (isUnloaded) {
      println("Warning: Attempting to call $methodName on an unloaded style")
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
