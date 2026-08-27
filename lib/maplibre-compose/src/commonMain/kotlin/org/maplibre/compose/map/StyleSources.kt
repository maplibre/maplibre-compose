package org.maplibre.compose.map

import androidx.compose.runtime.mutableStateOf
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.StyleMutationException

/**
 * The loaded style's sources, exposed on [MapState.sources].
 *
 * The collection reads a snapshot of the live style, refreshed on style load, on a
 * [MapState.baseStyle] change, when a source is added to or removed from the live style, and when a
 * source's TileJSON metadata arrives. While no style is loaded, [ids] is empty.
 *
 * For a source that the style content composed or that [add] added, [get] returns the live [Source]
 * instance, so data updates such as
 * [GeoJsonSource.setData][org.maplibre.compose.sources.GeoJsonSource.setData] work on it. For a
 * base-style source, [get] returns a descriptor reconstructed from the live style.
 *
 * [add] and [remove] mutate the loaded style directly. Each source id has one owner — the base
 * style, the style content composition, or the application through [add] — and only the owner may
 * remove it. See [MapState] for the reload rule that applies to every imperative style mutation.
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
   * The live source ids, in the order that the style declares them. The list describes the loaded
   * style only, so it is empty while no style is loaded even when [get] can resolve a
   * composition-owned source. A composition that reads this property recomposes when the snapshot
   * refreshes.
   */
  public val ids: List<String>
    get() = snapshotState.value.keys.toList()

  /**
   * Returns the source with [id], or null when the style has no such source. A source that the
   * style content owns resolves regardless of load state — before its id appears in [ids] — so a
   * data update such as [GeoJsonSource.setData][org.maplibre.compose.sources.GeoJsonSource.setData]
   * is legal on a detached state and applies when a style loads.
   */
  public operator fun get(id: String): Source? =
    state.styleNode.compositionSources[id]
      ?: state.styleNode.appSourceSnapshot[id]
      ?: snapshotState.value[id]

  /**
   * Adds [source] to the loaded style. The source is map-owned: the sync that applies the style
   * content never removes or re-adds it, and [get] returns this instance.
   *
   * A [MapState.baseStyle] reload drops the source; reapply it after the load.
   *
   * @throws IllegalArgumentException when a source with the id already exists; the message names
   *   the owner.
   * @throws IllegalStateException when no style is loaded, when the state is closed, or when
   *   MapLibre refuses the source.
   */
  public suspend fun add(source: Source) {
    state.host.runSerialized {
      val node = state.styleNode
      val binding = node.binding
      check(binding.isLoaded) { "No loaded style; a source can only be added to a loaded style" }
      node.ensureAppTablesFor(binding)
      val id = source.id
      require(id !in node.compositionSources) {
        "Source id '$id' is owned by the style content composition"
      }
      require(id !in node.appSources) { "Source id '$id' was already added through this state" }
      require(binding.sourceExists(id) != true && binding.getSource(id) == null) {
        "Source id '$id' already exists in the loaded style"
      }
      binding.addSource(source)
      node.appSources[id] = source
      node.publishAppSources()
      refreshSource(id)
    }
  }

  /**
   * Removes the source with [id], which [add] added, from the loaded style.
   *
   * @throws IllegalArgumentException when the loaded style has no source with [id].
   * @throws IllegalStateException when no style is loaded, when the state is closed, when the base
   *   style or the style content composition owns [id], or when a live layer still draws from the
   *   source.
   */
  public suspend fun remove(id: String) {
    state.host.runSerialized {
      val node = state.styleNode
      val binding = node.binding
      check(binding.isLoaded) {
        "No loaded style; a source can only be removed from a loaded style"
      }
      node.ensureAppTablesFor(binding)
      check(id !in node.compositionSources) {
        "Source '$id' is owned by the style content composition; remove it by recomposing the " +
          "content rather than through MapState.sources"
      }
      val source = node.appSources[id]
      if (source == null) {
        val existsInStyle = binding.sourceExists(id) == true || binding.getSource(id) != null
        check(!existsInStyle) {
          "Source '$id' belongs to the base style; select a different MapState.baseStyle to " +
            "change it"
        }
        throw IllegalArgumentException("The loaded style has no source with id '$id'")
      }
      val usedBy = binding.getLayers().firstOrNull { it.sourceId == id }
      check(usedBy == null) {
        "Source '$id' cannot be removed while layer '${usedBy?.id}' draws from it"
      }
      try {
        binding.removeSource(source)
      } catch (error: StyleMutationException) {
        throw IllegalStateException("Source '$id' cannot be removed: ${error.message}", error)
      }
      node.appSources.remove(id)
      node.publishAppSources()
      refreshSource(id)
    }
  }

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
