package org.maplibre.compose.map

import org.maplibre.compose.layers.LayerHandle

/**
 * The loaded style's layers, exposed on [MapState.layers].
 *
 * The collection reads through to the live style. While no style is loaded, [ids] is empty and
 * [get] returns null for every id.
 *
 * A layer id is mutable through one owner. A layer from the base style is map-owned, and its
 * [LayerHandle] accepts writes. A layer that the style content composed is composition-owned: [get]
 * returns a handle whose reads work, and whose writes throw [IllegalStateException].
 */
public class StyleLayers internal constructor(private val state: MapState) {

  /**
   * The live layer ids in draw order, bottom first. A composition that reads this property
   * recomposes when the list refreshes.
   *
   * The list refreshes on style load, on a [MapState.baseStyle] change, and on source changes. A
   * layer that the style content adds appears at the next refresh rather than at the add.
   */
  public val ids: List<String>
    get() = state.layerIdsState.value

  /** Returns a handle over the live layer with [id], or null when the style has no such layer. */
  public operator fun get(id: String): LayerHandle? {
    val descriptor = state.styleNode.binding.getLayer(id) ?: return null
    return LayerHandle(state, descriptor)
  }
}
