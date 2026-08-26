package org.maplibre.compose.style

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
  internal var state: StyleState? = null

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

  internal fun addReference(source: Source) {
    require(source.id !in baseSources()) { "Source ID '${source.id}' already exists in base style" }
    counter.increment(source) {
      desiredSources += source
      node.requestSync?.invoke()
    }
  }

  internal fun removeReference(source: Source) {
    require(source.id !in baseSources()) {
      "Source ID '${source.id}' is part of the base style and can't be removed here"
    }
    counter.decrement(source) {
      desiredSources -= source
      node.requestSync?.invoke()
    }
  }
}
