package org.maplibre.compose.style

import org.maplibre.compose.sources.Source

internal class SourceManager(private val node: StyleNode) {

  private val baseSources = node.style.getSources().associateBy { it.id }
  private val counter = ReferenceCounter<Source>()
  private val sourceIds = IncrementingId("source")

  /** Receives updates on changes to the style */
  internal var state: StyleState? = null

  internal fun getBaseSource(id: String): Source? {
    return baseSources[id]
  }

  internal fun nextId(): String = sourceIds.next()

  internal fun addReference(source: Source) {
    require(source.id !in baseSources) { "Source ID '${source.id}' already exists in base style" }
    counter.increment(source) {
      node.logger?.i { "Adding source ${source.id}" }
      node.style.addSource(source)
    }
  }

  internal fun removeReference(source: Source) {
    require(source.id !in baseSources) {
      "Source ID '${source.id}' is part of the base style and can't be removed here"
    }
    counter.decrement(source) {
      node.logger?.i { "Removing source ${source.id}" }
      node.style.removeSource(source)
      state?.reloadSources()
    }
  }

  internal fun applyChanges() {
    state?.reloadSources()
  }
}
