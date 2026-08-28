package org.maplibre.compose.map

import androidx.compose.runtime.mutableStateOf
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleMutationException

/**
 * The loaded style's sources, exposed on [MapState.sources].
 *
 * The collection reads a snapshot of the live style, refreshed on style load, on a
 * [MapState.baseStyle] change, when a source is added to or removed from the live style, and when a
 * source's TileJSON metadata arrives. While no style is loaded, [ids] is empty.
 *
 * For a source that the style composition composed or that [add] added, [get] returns the live
 * [Source] instance, so data updates such as
 * [GeoJsonSource.setData][org.maplibre.compose.sources.GeoJsonSource.setData] work on it. For a
 * base-style source, [get] returns a descriptor reconstructed from the live style.
 *
 * [add] and [remove] mutate the loaded style directly. Each source id has one owner — the base
 * style, the style composition, or the application through [add] — and only the owner may remove
 * it. See [MapState] for the reload rule that applies to every imperative style mutation.
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

  // A descriptor retained across a reload would route its operations into the unloaded binding.
  private var snapshotBinding: StyleBinding? = null

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
   * style composition owns resolves regardless of load state — before its id appears in [ids] — so
   * a data update such as
   * [GeoJsonSource.setData][org.maplibre.compose.sources.GeoJsonSource.setData] is legal on a
   * detached state and applies when a style loads.
   */
  public operator fun get(id: String): Source? {
    if (state.isClosed) return null
    val fromKernel = state.kernel.read { compositionSources[id] ?: appSources[id] }
    return fromKernel
      ?: state.styleNode.compositionSources[id]
      ?: state.styleNode.appSourceSnapshot[id]
      ?: snapshotState.value[id]
  }

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
    // No pre-check outside the serialized block: the published snapshot can trail the host-confined
    // truth in either direction, and a stale rejection would refuse a legal add.
    state.host.runSerialized {
      val node = state.styleNode
      val binding = node.binding
      check(binding.isLoaded) { "No loaded style; a source can only be added to a loaded style" }
      node.ensureAppTablesFor(binding)
      val id = source.id
      // The published snapshot can trail a reference recorded on the host, so ownership is decided
      // on the host-confined desired set.
      require(node.sourceManager.desiredSources.none { it.id == id }) {
        "Source id '$id' is owned by the style composition"
      }
      require(id !in node.appSources) { "Source id '$id' was already added through this state" }
      require(binding.sourceExists(id) != true && binding.getSource(id) == null) {
        "Source id '$id' is owned by the base style; select a different MapState.baseStyle to " +
          "change it"
      }
      // Reserve in the kernel first so publication and authorization share one generation.
      check(state.commitAppSource(binding, source)) {
        "Source '$id' was not added: the style unloaded during the add"
      }
      try {
        val stillCurrent = state.kernel.read { this.binding === binding && !closed }
        check(stillCurrent) { "Source '$id' was not added: the style unloaded during the add" }
        binding.addSource(source)
        check(source.binding === binding) {
          "Source '$id' was not added: the style unloaded during the add"
        }
      } catch (error: Throwable) {
        state.commitAppSourceRemoval(binding, id)
        throw error
      }
      refreshSource(id)
    }
  }

  /**
   * Removes the source with [id], which [add] added, from the loaded style.
   *
   * @throws IllegalArgumentException when the loaded style has no source with [id].
   * @throws IllegalStateException when no style is loaded, when the state is closed, when the base
   *   style or the style composition owns [id], or when a live layer still draws from the source.
   */
  public suspend fun remove(id: String) {
    state.host.runSerialized {
      val node = state.styleNode
      val binding = node.binding
      check(binding.isLoaded) {
        "No loaded style; a source can only be removed from a loaded style"
      }
      node.ensureAppTablesFor(binding)
      // The host-confined desired set, not the published snapshot, decides ownership here too.
      check(node.sourceManager.desiredSources.none { it.id == id }) {
        "Source '$id' is owned by the style composition; remove it by recomposing the " +
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
      check(state.commitAppSourceRemoval(binding, id)) {
        "Source '$id' was not removed: the style unloaded during the removal"
      }
      try {
        binding.removeSource(source)
      } catch (error: StyleMutationException) {
        state.commitAppSource(binding, source)
        throw IllegalStateException("Source '$id' cannot be removed: ${error.message}", error)
      } catch (error: Throwable) {
        state.commitAppSource(binding, source)
        throw error
      }
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
    val binding = state.styleNode.binding
    if (!binding.isLoaded) return
    if (binding !== snapshotBinding) return refreshSources()

    val current = snapshotState.value
    val refreshed = binding.getSource(id)
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
    val binding = state.styleNode.binding
    if (!binding.isLoaded) return

    val current = if (binding === snapshotBinding) snapshotState.value else emptyMap()
    snapshotBinding = binding
    val refreshed = binding.getSources().associateBy { it.id }
    var changed =
      current.keys.toList() != refreshed.keys.toList() || current !== snapshotState.value
    val reconciled = refreshed.mapValues { (id, source) ->
      current[id]?.takeIf { source.hasSameState(it) } ?: source.also { changed = true }
    }
    if (changed) snapshotState.value = reconciled
  }

  private fun Source.hasSameState(other: Source?): Boolean =
    other != null && this::class == other::class && attributionHtml == other.attributionHtml
}
