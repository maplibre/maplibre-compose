package org.maplibre.compose.style

import org.maplibre.compose.map.StyleSources
import org.maplibre.compose.sources.Source

/**
 * The ref-counted desired source set. Reference changes record desired state only; the engine
 * mutations happen when [StyleApplier] applies [DesiredStyleRevision].
 */
internal class SourceManager(private val node: StyleNode) {

  private val counter = ReferenceCounter<Source>()
  private val sourceIds = IncrementingId("source")

  /** The sources the composition currently wants in the style, in reference order. */
  internal val desiredSources = LinkedHashSet<Source>()

  /** Receives updates on changes to the style */
  internal var sources: StyleSources? = null

  internal fun getBaseSource(id: String): Source? {
    return node.baseStyle().sources[id]
  }

  internal fun nextId(): String = sourceIds.next()

  /**
   * Records a reference to [source] unless it is the base source of that id, and returns whether a
   * reference was taken; only a taken reference may be given back through [removeReference].
   */
  internal fun addReference(source: Source): Boolean {
    val base = node.baseStyle().sources[source.id]
    if (base === source) return false
    require(base == null) { "Source id '${source.id}' conflicts with a base source" }
    require(!node.appSourceOwned(source.id)) {
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
