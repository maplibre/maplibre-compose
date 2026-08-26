package org.maplibre.compose.map

import org.maplibre.compose.sources.Source

/**
 * The loaded style's sources, exposed on [MapState.sources].
 *
 * The collection reads through to the live style. While no style is loaded, [ids] is empty and
 * [get] returns null for every id.
 *
 * For a source that the style content composed, [get] returns the live [Source] instance the
 * content owns, so data updates such as
 * [GeoJsonSource.setData][org.maplibre.compose.sources.GeoJsonSource.setData] work on it. For a
 * base-style source, [get] returns a descriptor reconstructed from the live style.
 */
public class StyleSources internal constructor(private val state: MapState) {

  /**
   * The live source ids. A composition that reads this property recomposes when the list refreshes.
   *
   * The list refreshes on style load, on a [MapState.baseStyle] change, and when a source is added
   * to or removed from the live style.
   */
  public val ids: List<String>
    get() = state.sourceIdsState.value

  /** Returns the source with [id], or null when the style has no such source. */
  public operator fun get(id: String): Source? =
    state.styleNode.compositionSources[id] ?: state.styleNode.binding.getSource(id)

  /**
   * The distinct attribution texts of every source in the style, in the order that the style
   * declares them. Sources that declare no attribution are skipped. A composition that reads this
   * property recomposes when the attributions change.
   */
  public val attributions: List<String>
    get() =
      state.styleState.sources.values
        .map { it.attributionHtml }
        .filter { it.isNotEmpty() }
        .distinct()
}
