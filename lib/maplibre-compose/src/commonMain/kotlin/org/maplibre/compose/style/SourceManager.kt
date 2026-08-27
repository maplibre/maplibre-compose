package org.maplibre.compose.style

import org.maplibre.compose.map.StyleSources
import org.maplibre.compose.sources.Source

/**
 * The ref-counted desired source set. Reference changes record desired state only; the engine
 * mutations happen when [StyleNode.applyChanges] syncs.
 */
internal class SourceManager(private val node: StyleNode) {

  private val counter = ReferenceCounter<Source>()
  private val sourceIds = IncrementingId("source")

  private var baseSourcesFor: StyleBinding? = null
  private var baseSources: Map<String, Source> = emptyMap()

  /** The sources the composition currently wants in the style, in reference order. */
  internal val desiredSources = LinkedHashSet<Source>()

  /** Receives updates on changes to the style */
  internal var sources: StyleSources? = null

  /** Recaptures the base set from the current binding; only valid before user sources are added. */
  internal fun captureBaseSources() {
    baseSourcesFor = null
    baseSources()
  }

  private fun baseSources(): Map<String, Source> {
    val binding = node.binding
    if (baseSourcesFor !== binding) {
      baseSources = binding.getSources().associateBy { it.id }
      baseSourcesFor = binding
    }
    return baseSources
  }

  internal fun getBaseSource(id: String): Source? {
    return baseSources()[id]
  }

  internal fun nextId(): String = sourceIds.next()

  /**
   * Records a reference to [source] unless it is the base source of that id, and returns whether a
   * reference was taken; only a taken reference may be given back through [removeReference].
   */
  internal fun addReference(source: Source): Boolean {
    val base = baseSources()[source.id]
    if (base === source) return false
    require(base == null) { "Source id '${source.id}' conflicts with a base source" }
    require(source.id !in node.appSourceSnapshot) {
      "Source id '${source.id}' conflicts with a source added through MapState.sources"
    }
    counter.increment(source) {
      desiredSources += source
      node.requestSync()
    }
    return true
  }

  internal fun removeReference(source: Source) {
    counter.decrement(source) {
      desiredSources -= source
      node.requestSync()
    }
  }
}
