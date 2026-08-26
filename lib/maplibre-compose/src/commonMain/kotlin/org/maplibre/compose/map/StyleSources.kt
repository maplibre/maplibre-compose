package org.maplibre.compose.map

import androidx.compose.runtime.mutableStateOf
import org.maplibre.compose.sources.Source

/**
 * The loaded style's sources, exposed on [MapState.sources].
 *
 * The collection reads a snapshot of the live style, refreshed on style load, on a
 * [MapState.baseStyle] change, when a source is added to or removed from the live style, and when a
 * source's TileJSON metadata arrives. While no style is loaded, [ids] is empty and [get] returns
 * null for every id.
 *
 * For a source that the style content composed, [get] returns the live [Source] instance the
 * content owns, so data updates such as
 * [GeoJsonSource.setData][org.maplibre.compose.sources.GeoJsonSource.setData] work on it. For a
 * base-style source, [get] returns a descriptor reconstructed from the live style.
 */
public class StyleSources internal constructor(private val state: MapState) {

  /**
   * The snapshot the public reads below share; refreshed as the live style reports changes.
   *
   * Tests of the refresh path read this for what the public views cannot express: an empty
   * attribution, which [attributions] filters out, and the instance identity of a reconciled
   * source.
   */
  internal val snapshot: Map<String, Source>
    get() = snapshotState.value

  private val snapshotState = mutableStateOf(emptyMap<String, Source>())

  init {
    state.styleNode.sourceManager.sources = this
  }

  /**
   * The live source ids, in the order that the style declares them. A composition that reads this
   * property recomposes when the snapshot refreshes.
   */
  public val ids: List<String>
    get() = snapshotState.value.keys.toList()

  /** Returns the source with [id], or null when the style has no such source. */
  public operator fun get(id: String): Source? =
    state.styleNode.compositionSources[id] ?: snapshotState.value[id]

  /**
   * The distinct attribution texts of every source in the style, in the order that the style
   * declares them. Sources that declare no attribution are skipped. A composition that reads this
   * property recomposes when the attributions change.
   */
  public val attributions: List<String>
    get() =
      snapshotState.value.values.map { it.attributionHtml }.filter { it.isNotEmpty() }.distinct()

  /** Empties the snapshot; a detached state stops reporting a dead map's sources. */
  internal fun clear() {
    if (snapshotState.value.isNotEmpty()) snapshotState.value = emptyMap()
  }

  internal fun refreshSource(id: String) {
    if (!state.styleNode.binding.isLoaded) return

    val current = snapshotState.value
    val refreshed = state.styleNode.binding.getSource(id)
    val previous = current[id]
    when {
      refreshed == null && previous != null -> snapshotState.value = current - id
      refreshed != null && !refreshed.hasSameState(previous) ->
        snapshotState.value = current + (id to refreshed)
    }
  }

  internal fun refreshSources() {
    // An unloaded binding during a style switch keeps the old snapshot, so the attribution UI
    // never flickers empty between styles.
    if (!state.styleNode.binding.isLoaded) return

    val current = snapshotState.value
    val refreshed = state.styleNode.binding.getSources().associateBy { it.id }
    var changed = current.keys.toList() != refreshed.keys.toList()
    val reconciled = refreshed.mapValues { (id, source) ->
      current[id]?.takeIf { source.hasSameState(it) } ?: source.also { changed = true }
    }
    if (changed) snapshotState.value = reconciled
  }

  private fun Source.hasSameState(other: Source?): Boolean =
    other != null && this::class == other::class && attributionHtml == other.attributionHtml
}
