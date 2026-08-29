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
    return state.record.read { compositionSources[id] ?: appSources[id] } ?: snapshotState.value[id]
  }

  /**
   * Adds [source] to the loaded style. The source is map-owned: the sync that applies the style
   * content never removes or re-adds it, and [get] returns this instance.
   *
   * A [MapState.baseStyle] reload drops the source; reapply it after the load.
   *
   * @throws IllegalArgumentException when a source with the id already exists, or when [source]
   *   already belongs to another [MapState]; the message names the owner.
   * @throws IllegalStateException when no style is loaded, when the state is closed, or when
   *   MapLibre refuses the source.
   */
  public suspend fun add(source: Source) {
    state.runStyleEffect { binding ->
      addAccepted(binding, source)?.let { throw it }
      refreshSource(source.id)
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
    state.runStyleEffect { binding ->
      removeAccepted(binding, id)?.let { throw it }
      refreshSource(id)
    }
  }

  private fun addAccepted(binding: StyleBinding, source: Source): Throwable? {
    val id = source.id
    val refusal =
      state.record.read {
        when {
          closed ->
            IllegalStateException("MapState is closed; a closed state cannot mutate the style")
          this.binding !== binding ->
            IllegalStateException("Source '$id' was not added: the style unloaded during the add")
          id in compositionSources ->
            IllegalArgumentException("Source id '$id' is owned by the style composition")
          id in appSources ->
            IllegalArgumentException("Source id '$id' was already added through this state")
          source.map != null && source.map !== state ->
            IllegalArgumentException("Source '$id' already belongs to another MapState")
          else -> null
        }
      }
    if (refusal != null) return refusal
    if (!binding.isLoaded) {
      return IllegalStateException("No loaded style; a source can only be added to a loaded style")
    }
    if (binding.sourceExists(id) == true || binding.getSource(id) != null) {
      return IllegalArgumentException(
        "Source id '$id' is owned by the base style; select a different MapState.baseStyle to " +
          "change it"
      )
    }
    return try {
      if (!binding.addSource(source)) {
        IllegalStateException("Source '$id' was not added: the style unloaded during the add")
      } else if (!state.commitAppSource(binding, source)) {
        IllegalStateException("Source '$id' was not added: the style unloaded during the add")
      } else {
        null
      }
    } catch (error: Throwable) {
      error
    }
  }

  private fun removeAccepted(binding: StyleBinding, id: String): Throwable? {
    val (refusal, appSource) =
      state.record.read {
        when {
          closed ->
            IllegalStateException("MapState is closed; a closed state cannot mutate the style") to
              null
          this.binding !== binding ->
            IllegalStateException(
              "Source '$id' was not removed: the style unloaded during the removal"
            ) to null
          id in compositionSources ->
            IllegalStateException(
              "Source '$id' is owned by the style composition; remove it by recomposing the " +
                "content rather than through MapState.sources"
            ) to null
          else -> null to appSources[id]
        }
      }
    if (refusal != null) return refusal
    if (!binding.isLoaded) {
      return IllegalStateException(
        "No loaded style; a source can only be removed from a loaded style"
      )
    }
    if (appSource == null) {
      val existsInStyle = binding.sourceExists(id) == true || binding.getSource(id) != null
      return if (existsInStyle) {
        IllegalStateException(
          "Source '$id' belongs to the base style; select a different MapState.baseStyle to " +
            "change it"
        )
      } else {
        IllegalArgumentException("The loaded style has no source with id '$id'")
      }
    }
    val usedBy = binding.getLayers().firstOrNull { it.sourceId == id }
    if (usedBy != null) {
      return IllegalStateException(
        "Source '$id' cannot be removed while layer '${usedBy.id}' draws from it"
      )
    }
    return try {
      binding.removeSource(id)
      if (!state.commitAppSourceRemoval(binding, id)) {
        IllegalStateException("Source '$id' was not removed: the style unloaded during the removal")
      } else {
        null
      }
    } catch (error: StyleMutationException) {
      IllegalStateException("Source '$id' cannot be removed: ${error.message}", error)
    } catch (error: Throwable) {
      error
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
    snapshotBinding = null
    if (snapshotState.value.isNotEmpty()) snapshotState.value = emptyMap()
  }

  internal fun refreshSource(id: String) {
    val binding = state.record.read { this.binding }
    if (!binding.isLoaded) return
    if (binding !== snapshotBinding) return refreshSources()

    val refreshed = binding.getSource(id)
    val latest = snapshotState.value
    val previous = latest[id]
    when {
      refreshed == null && previous != null -> snapshotState.value = latest - id
      refreshed != null && !refreshed.hasSameState(previous) ->
        snapshotState.value = latest + (id to refreshed)
    }
  }

  internal fun refreshSources() {
    // An unloaded binding during a style switch keeps the old snapshot, so the attribution UI
    // never flickers empty between styles.
    val binding = state.record.read { this.binding }
    if (!binding.isLoaded) return
    val refreshed = binding.getSources().associateBy { it.id }
    val current = if (binding === snapshotBinding) snapshotState.value else emptyMap()
    snapshotBinding = binding
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
